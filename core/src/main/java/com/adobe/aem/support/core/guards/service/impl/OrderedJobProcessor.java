package com.adobe.aem.support.core.guards.service.impl;

import com.adobe.aem.support.core.guards.cluster.ClusterLeaderService;
import com.adobe.aem.support.core.guards.persistence.JobPersistenceService;
import com.adobe.aem.support.core.guards.persistence.JobPersistenceService.JobPersistenceException;
import com.adobe.aem.support.core.guards.persistence.JobPersistenceService.PersistedJob;
import com.adobe.aem.support.core.guards.service.GuardedJob;
import com.adobe.aem.support.core.guards.service.JobProcessor;
import com.adobe.aem.support.core.guards.token.GuardedOrderTokenService;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OSGi implementation of {@link JobProcessor} that processes jobs in token
 * order,
 * grouped by topic.
 * 
 * <p>
 * <b>Distributed Architecture:</b>
 * </p>
 * <ol>
 * <li>Any AEM instance can receive job submissions via HTTP</li>
 * <li>Jobs are persisted to JCR</li>
 * <li><b>Only the cluster leader</b> polls JCR and processes jobs</li>
 * <li>Jobs are processed in token order (global ordering across all
 * instances)</li>
 * <li>Jobs are deleted from JCR after successful processing</li>
 * </ol>
 * 
 * <p>
 * A configurable coalesce time allows jobs arriving from different machines
 * to be properly ordered before processing begins.
 * </p>
 * 
 * <p>
 * Jobs that exceed the configured timeout are cancelled to prevent
 * queue bottlenecking and high heap usage.
 * </p>
 */
@Component(service = JobProcessor.class, immediate = true)
@Designate(ocd = OrderedJobProcessor.Config.class)
public class OrderedJobProcessor implements JobProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(OrderedJobProcessor.class);

    @ObjectClassDefinition(name = "Ordered Job Processor", description = "Configuration for the ordered job processor")
    @interface Config {
        @AttributeDefinition(name = "Coalesce Time (ms)", description = "Time to wait for more jobs before processing. Allows jobs from different machines to be properly ordered.")
        long coalesceTimeMs() default 50;

        @AttributeDefinition(name = "Job Timeout (seconds)", description = "Maximum time a job is allowed to run before being cancelled. Set to 0 to disable timeout.")
        long jobTimeoutSeconds() default 30;

        @AttributeDefinition(name = "Job Poll Interval (ms)", description = "How often the leader polls JCR for new jobs.")
        long jobPollIntervalMs() default 1000;
    }

    @Reference
    private GuardedOrderTokenService tokenService;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private volatile JobPersistenceService persistenceService;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private volatile ClusterLeaderService clusterLeaderService;

    // Map of job name -> GuardedJob implementation
    private final Map<String, GuardedJob<?>> registeredJobs = new ConcurrentHashMap<>();

    // Track jobs currently being processed to avoid re-polling them
    private final Set<String> processingJobs = ConcurrentHashMap.newKeySet();

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    protected void bindGuardedJob(GuardedJob<?> job) {
        registeredJobs.put(job.getName(), job);
        LOG.debug("Registered job implementation: {}", job.getName());
    }

    protected void unbindGuardedJob(GuardedJob<?> job) {
        registeredJobs.remove(job.getName());
        LOG.debug("Unregistered job implementation: {}", job.getName());
    }

    // Per-topic priority executors for sequential processing in timestamp order
    private final Map<String, PriorityExecutor> topicExecutors = new ConcurrentHashMap<>();

    /**
     * A single-threaded executor that processes jobs in priority order (by
     * timestamp).
     * This ensures that even if a job with an earlier timestamp arrives late,
     * it will still be processed before jobs with later timestamps.
     */
    private static class PriorityExecutor {
        private final ThreadPoolExecutor executor;

        PriorityExecutor(String topic) {
            // Comparator: first by timestamp, then by sequence number (for same-timestamp
            // jobs)
            Comparator<Runnable> comparator = Comparator
                    .comparingLong((Runnable r) -> ((PrioritizedJobRunnable) r).timestamp)
                    .thenComparingLong(r -> ((PrioritizedJobRunnable) r).sequenceNumber);

            PriorityBlockingQueue<Runnable> queue = new PriorityBlockingQueue<>(100, comparator);
            this.executor = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    queue,
                    r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        t.setName("topic-executor-" + topic);
                        return t;
                    });
        }

        void submit(PrioritizedJobRunnable job) {
            executor.execute(job);
        }

        void shutdown() {
            executor.shutdown();
        }

        void shutdownNow() {
            executor.shutdownNow();
        }
    }

    /**
     * A Runnable wrapper that carries the job's timestamp for priority ordering.
     * Jobs with lower timestamps (earlier) have higher priority.
     * The sequence number serves as a tie-breaker for jobs with identical
     * timestamps.
     */
    private static class PrioritizedJobRunnable implements Runnable {
        private final long timestamp;
        private final long sequenceNumber;
        private final Runnable task;

        // Tie-breaker sequence for jobs with the same timestamp (preserves insertion
        // order)
        private static final AtomicLong sequencer = new AtomicLong(0);

        PrioritizedJobRunnable(long timestamp, Runnable task) {
            this.timestamp = timestamp;
            this.task = task;
            this.sequenceNumber = sequencer.incrementAndGet();
        }

        @Override
        public void run() {
            task.run();
        }
    }

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> jobPollerFuture;
    private long coalesceTimeMs;
    private long jobTimeoutSeconds;
    private long jobPollIntervalMs;
    private volatile boolean shutdown = false;
    private volatile long lastJobSeenTime = 0;

    @Activate
    protected void activate(Config config) {
        this.coalesceTimeMs = Math.max(0, config.coalesceTimeMs());
        this.jobTimeoutSeconds = Math.max(0, config.jobTimeoutSeconds());
        this.jobPollIntervalMs = Math.max(100, config.jobPollIntervalMs());
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("job-processor-scheduler");
            return t;
        });
        LOG.info("OrderedJobProcessor activated with coalesceTimeMs={}, jobTimeoutSeconds={}, jobPollIntervalMs={}",
                coalesceTimeMs, jobTimeoutSeconds, jobPollIntervalMs);

        // Start the job poller (only does work if leader)
        startJobPoller();
    }

    @Deactivate
    protected void deactivate() {
        shutdownNow();
    }

    /**
     * Starts a scheduled task that polls JCR for new jobs.
     * Only the leader will actually process jobs.
     */
    private void startJobPoller() {
        jobPollerFuture = scheduler.scheduleWithFixedDelay(
                this::pollAndProcessJobs,
                jobPollIntervalMs,
                jobPollIntervalMs,
                TimeUnit.MILLISECONDS);
        LOG.debug("Job poller started with interval {}ms", jobPollIntervalMs);
    }

    /**
     * Polls JCR for pending jobs and processes them in token order.
     * Only runs on the leader instance.
     */
    private void pollAndProcessJobs() {
        if (shutdown) {
            return;
        }

        JobPersistenceService persistence = this.persistenceService;
        if (persistence == null) {
            return;
        }

        ClusterLeaderService leaderService = this.clusterLeaderService;
        if (leaderService != null && !leaderService.isLeader()) {
            return; // Not the leader
        }

        try {
            // JCR query returns jobs already sorted by timestamp with a limit (e.g., 100)
            List<PersistedJob> persistedJobs = persistence.loadAll();
            if (persistedJobs.isEmpty()) {
                return;
            }

            // Apply coalesce timing - wait if we just saw new jobs
            long now = System.currentTimeMillis();
            if (now - lastJobSeenTime < coalesceTimeMs) {
                LOG.debug("Coalescing - waiting for more jobs");
                return;
            }
            lastJobSeenTime = now;

            LOG.debug("Processing {} jobs from JCR (already sorted by timestamp)", persistedJobs.size());

            // Group by topic for parallel processing across topics
            // Jobs within each topic remain in timestamp order
            Map<String, List<PersistedJob>> jobsByTopic = new LinkedHashMap<>();
            for (PersistedJob job : persistedJobs) {
                jobsByTopic.computeIfAbsent(job.getTopic(), k -> new ArrayList<>()).add(job);
            }

            // Process each topic's jobs
            for (Map.Entry<String, List<PersistedJob>> entry : jobsByTopic.entrySet()) {
                String topic = entry.getKey();
                List<PersistedJob> topicJobs = entry.getValue();

                // Process jobs for this topic sequentially in token order
                processTopicJobs(topic, topicJobs, persistence);
            }

        } catch (JobPersistenceException e) {
            LOG.error("Failed to poll for persisted jobs", e);
        }
    }

    /**
     * Processes jobs for a single topic sequentially in token order.
     * 
     * <p>
     * Uses a priority queue executor to ensure jobs are always processed
     * in timestamp order, even if a job with an earlier timestamp arrives late
     * (e.g., due to network delays).
     * </p>
     */
    private void processTopicJobs(String topic, List<PersistedJob> jobs, JobPersistenceService persistence) {
        PriorityExecutor topicExecutor = topicExecutors.computeIfAbsent(topic, PriorityExecutor::new);

        for (PersistedJob persistedJob : jobs) {
            // Skip if already being processed
            if (!processingJobs.add(persistedJob.getPersistenceId())) {
                continue;
            }

            GuardedJob<?> jobImpl = registeredJobs.get(persistedJob.getJobName());
            if (jobImpl == null) {
                LOG.warn("No implementation found for job '{}'. Removing from persistence.",
                        persistedJob.getJobName());
                removeFromPersistence(persistence, persistedJob.getPersistenceId());
                processingJobs.remove(persistedJob.getPersistenceId());
                continue;
            }

            // Extract timestamp from token for priority ordering
            long timestamp = tokenService.extractTimestamp(persistedJob.getToken());

            // Submit with priority based on timestamp (lower = higher priority = earlier
            // execution)
            Runnable task = () -> {
                try {
                    executeJob(persistedJob, jobImpl);
                } finally {
                    // Remove from JCR after execution (success or failure)
                    removeFromPersistence(persistence, persistedJob.getPersistenceId());
                    processingJobs.remove(persistedJob.getPersistenceId());
                }
            };

            topicExecutor.submit(new PrioritizedJobRunnable(timestamp, task));
            LOG.debug("Queued job for execution: topic={}, timestamp={}, id={}",
                    topic, timestamp, persistedJob.getPersistenceId());
        }
    }

    /**
     * Executes a single job with timeout handling.
     */
    private void executeJob(PersistedJob persistedJob, GuardedJob<?> jobImpl) {
        String jobName = persistedJob.getJobName();
        String topic = persistedJob.getTopic();
        String token = persistedJob.getToken();

        // INFO level log with token for execution order verification
        LOG.info("Executing job: topic={}, jobName={}, token={}",
                topic, jobName, token);

        // Determine timeout: use job-specific timeout if set, otherwise use global default
        long jobSpecificTimeout = jobImpl.getTimeoutSeconds();
        long effectiveTimeout = (jobSpecificTimeout >= 0) ? jobSpecificTimeout : jobTimeoutSeconds;
        
        // For async jobs, always enforce a timeout to prevent circular polling issues
        if (jobImpl.isAsync() && effectiveTimeout <= 0) {
            effectiveTimeout = jobTimeoutSeconds > 0 ? jobTimeoutSeconds : 30; // Fallback to 30s if global is also disabled
            LOG.warn("Async job '{}' has no timeout configured. Using default timeout of {} seconds to prevent runaway polling.",
                    jobName, effectiveTimeout);
        }
        
        if (effectiveTimeout <= 0) {
            // No timeout, execute directly (only for sync jobs)
            try {
                jobImpl.execute(persistedJob.getParameters());
                LOG.debug("Job completed: {}", persistedJob.getPersistenceId());
            } catch (Exception e) {
                LOG.error("Job '{}' in topic '{}' failed: {}", jobName, topic, e.getMessage(), e);
            }
            return;
        }

        // Execute with timeout
        ExecutorService jobExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("job-runner-" + jobName);
            return t;
        });

        final long timeoutSeconds = effectiveTimeout;
        Future<?> jobFuture = jobExecutor.submit(() -> {
            try {
                long startTime = System.currentTimeMillis();
                long timeoutMs = timeoutSeconds * 1000;
                
                jobImpl.execute(persistedJob.getParameters());
                
                // For async jobs, poll until complete or timeout
                if (jobImpl.isAsync()) {
                    boolean firstCheck = true;
                    while (!jobImpl.isComplete(persistedJob.getParameters())) {
                        firstCheck = false;
                        long elapsed = System.currentTimeMillis() - startTime;
                        if (elapsed >= timeoutMs) {
                            throw new RuntimeException(String.format(
                                "Async job '%s' did not complete within %d seconds", jobName, timeoutSeconds));
                        }
                        
                        long remainingMs = timeoutMs - elapsed;
                        long sleepMs = Math.min(jobImpl.getAsyncPollingIntervalMs(), remainingMs);
                        if (sleepMs > 0) {
                            Thread.sleep(sleepMs);
                        }
                    }
                    
                    // Warn if isComplete() returned true immediately (likely forgot to override)
                    if (firstCheck) {
                        LOG.warn("Async job '{}' returned isComplete()=true immediately after execute(). " +
                                "This likely means isComplete() was not overridden. " +
                                "The job will be marked as complete without polling for async completion.",
                                jobName);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Job interrupted", e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try {
            jobFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            LOG.debug("Job completed: {}", persistedJob.getPersistenceId());
        } catch (TimeoutException e) {
            LOG.warn("Job '{}' in topic '{}' cancelled after {} seconds (timeout). " +
                    "This may indicate a stuck job causing queue bottlenecking.",
                    jobName, topic, timeoutSeconds);
            jobFuture.cancel(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Job '{}' in topic '{}' was interrupted", jobName, topic);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String errorMessage = cause != null ? cause.getMessage() : e.getMessage();
            LOG.error("Job '{}' in topic '{}' failed: {}", jobName, topic, errorMessage);
        } finally {
            jobExecutor.shutdownNow();
        }
    }

    private void removeFromPersistence(JobPersistenceService persistence, String persistenceId) {
        try {
            persistence.remove(persistenceId);
            LOG.debug("Removed job from JCR: {}", persistenceId);
        } catch (JobPersistenceException e) {
            LOG.warn("Failed to remove job from JCR: {}", persistenceId, e);
        }
    }

    @Override
    public <T> CompletableFuture<T> submit(String topic, String token, GuardedJob<T> job,
            Map<String, Object> parameters) {
        validateSubmission(topic, token, job);

        if (shutdown) {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(new RejectedExecutionException("Processor has been shut down"));
            return future;
        }

        JobPersistenceService persistence = this.persistenceService;
        if (persistence == null) {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Persistence service not available"));
            return future;
        }

        try {
            // Extract user ID from parameters (set by JobSubmitServlet)
            String submittedBy = (String) parameters.get("_submittedBy");
            String persistenceId = persistence.persist(topic, token, job.getName(), submittedBy, parameters);
            LOG.info("Job submitted and persisted: topic={}, jobName={}, submittedBy={}, id={}",
                    topic, job.getName(), submittedBy, persistenceId);

            // Update last seen time to trigger coalesce
            lastJobSeenTime = System.currentTimeMillis();

            // Return completed future - actual execution happens via poller on leader
            // Result is not available since execution happens asynchronously
            CompletableFuture<T> future = new CompletableFuture<>();
            future.complete(null);
            return future;

        } catch (JobPersistenceException e) {
            LOG.error("Failed to persist job: topic={}, jobName={}", topic, job.getName(), e);
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    private void validateSubmission(String topic, String token, GuardedJob<?> job) {
        if (topic == null || topic.trim().isEmpty()) {
            throw new IllegalArgumentException("Topic must not be null or empty");
        }
        if (token == null || !tokenService.isValid(token)) {
            throw new IllegalArgumentException("Token is null or invalid");
        }
        if (job == null) {
            throw new IllegalArgumentException("Job must not be null");
        }
    }

    @Override
    public int getPendingCount(String topic) {
        // Count jobs in JCR for this topic
        JobPersistenceService persistence = this.persistenceService;
        if (persistence == null) {
            return 0;
        }
        try {
            return (int) persistence.loadAll().stream()
                    .filter(job -> topic.equals(job.getTopic()))
                    .count();
        } catch (JobPersistenceException e) {
            LOG.warn("Failed to count pending jobs for topic: {}", topic, e);
            return 0;
        }
    }

    @Override
    public int getTotalPendingCount() {
        JobPersistenceService persistence = this.persistenceService;
        if (persistence == null) {
            return 0;
        }
        try {
            return persistence.loadAll().size();
        } catch (JobPersistenceException e) {
            LOG.warn("Failed to count total pending jobs", e);
            return 0;
        }
    }

    @Override
    public void shutdown() {
        shutdown = true;
        if (jobPollerFuture != null) {
            jobPollerFuture.cancel(false);
        }
        topicExecutors.values().forEach(PriorityExecutor::shutdown);
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Override
    public void shutdownNow() {
        shutdown = true;
        if (jobPollerFuture != null) {
            jobPollerFuture.cancel(true);
        }
        topicExecutors.values().forEach(PriorityExecutor::shutdownNow);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }
}
