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

/**
 * OSGi implementation of {@link JobProcessor} that processes jobs in token order,
 * grouped by topic.
 * 
 * <p><b>Distributed Architecture:</b></p>
 * <ol>
 *   <li>Any AEM instance can receive job submissions via HTTP</li>
 *   <li>Jobs are persisted to JCR</li>
 *   <li><b>Only the cluster leader</b> polls JCR and processes jobs</li>
 *   <li>Jobs are processed in token order (global ordering across all instances)</li>
 *   <li>Jobs are deleted from JCR after successful processing</li>
 * </ol>
 * 
 * <p>A configurable coalesce time allows jobs arriving from different machines
 * to be properly ordered before processing begins.</p>
 * 
 * <p>Jobs that exceed the configured timeout are cancelled to prevent
 * queue bottlenecking and high heap usage.</p>
 */
@Component(service = JobProcessor.class, immediate = true)
@Designate(ocd = OrderedJobProcessor.Config.class)
public class OrderedJobProcessor implements JobProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(OrderedJobProcessor.class);

    @ObjectClassDefinition(
        name = "Ordered Job Processor",
        description = "Configuration for the ordered job processor"
    )
    @interface Config {
        @AttributeDefinition(
            name = "Coalesce Time (ms)",
            description = "Time to wait for more jobs before processing. Allows jobs from different machines to be properly ordered."
        )
        long coalesceTimeMs() default 50;

        @AttributeDefinition(
            name = "Job Timeout (seconds)",
            description = "Maximum time a job is allowed to run before being cancelled. Set to 0 to disable timeout."
        )
        long jobTimeoutSeconds() default 30;

        @AttributeDefinition(
            name = "Job Poll Interval (ms)",
            description = "How often the leader polls JCR for new jobs."
        )
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

    @Reference(
        cardinality = ReferenceCardinality.MULTIPLE,
        policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY
    )
    protected void bindGuardedJob(GuardedJob<?> job) {
        registeredJobs.put(job.getName(), job);
        LOG.debug("Registered job implementation: {}", job.getName());
    }

    protected void unbindGuardedJob(GuardedJob<?> job) {
        registeredJobs.remove(job.getName());
        LOG.debug("Unregistered job implementation: {}", job.getName());
    }

    // Per-topic executors for sequential processing within topics
    private final Map<String, ExecutorService> topicExecutors = new ConcurrentHashMap<>();
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
            TimeUnit.MILLISECONDS
        );
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
     */
    private void processTopicJobs(String topic, List<PersistedJob> jobs, JobPersistenceService persistence) {
        ExecutorService topicExecutor = topicExecutors.computeIfAbsent(topic, t -> 
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r);
                thread.setDaemon(true);
                thread.setName("topic-executor-" + t);
                return thread;
            }));

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

            // Submit for sequential execution within the topic
            topicExecutor.submit(() -> {
                try {
                    executeJob(persistedJob, jobImpl);
                } finally {
                    // Remove from JCR after execution (success or failure)
                    removeFromPersistence(persistence, persistedJob.getPersistenceId());
                    processingJobs.remove(persistedJob.getPersistenceId());
                }
            });
        }
    }

    /**
     * Executes a single job with timeout handling.
     */
    private void executeJob(PersistedJob persistedJob, GuardedJob<?> jobImpl) {
        String jobName = persistedJob.getJobName();
        String topic = persistedJob.getTopic();
        
        LOG.debug("Executing job: topic={}, name={}, id={}", 
            topic, jobName, persistedJob.getPersistenceId());

        if (jobTimeoutSeconds <= 0) {
            // No timeout, execute directly
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

        Future<?> jobFuture = jobExecutor.submit(() -> {
            try {
                jobImpl.execute(persistedJob.getParameters());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try {
            jobFuture.get(jobTimeoutSeconds, TimeUnit.SECONDS);
            LOG.debug("Job completed: {}", persistedJob.getPersistenceId());
        } catch (TimeoutException e) {
            LOG.warn("Job '{}' in topic '{}' cancelled after {} seconds (timeout). " +
                     "This may indicate a stuck job causing queue bottlenecking.",
                     jobName, topic, jobTimeoutSeconds);
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
    public <T> CompletableFuture<T> submit(String topic, String token, GuardedJob<T> job, Map<String, Object> parameters) {
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
            String persistenceId = persistence.persist(topic, token, job.getName(), parameters);
            LOG.info("Job submitted and persisted: topic={}, jobName={}, id={}", 
                topic, job.getName(), persistenceId);
            
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
        topicExecutors.values().forEach(ExecutorService::shutdown);
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
        topicExecutors.values().forEach(ExecutorService::shutdownNow);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }
}
