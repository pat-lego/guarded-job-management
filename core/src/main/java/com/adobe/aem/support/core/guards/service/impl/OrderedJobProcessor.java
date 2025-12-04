package com.adobe.aem.support.core.guards.service.impl;

import com.adobe.aem.support.core.guards.persistence.JobPersistenceService;
import com.adobe.aem.support.core.guards.persistence.JobPersistenceService.JobPersistenceException;
import com.adobe.aem.support.core.guards.persistence.JobPersistenceService.PersistedJob;
import com.adobe.aem.support.core.guards.service.GuardedJob;
import com.adobe.aem.support.core.guards.service.JobProcessor;
import com.adobe.aem.support.core.guards.service.OrderedJobQueue;
import com.adobe.aem.support.core.guards.token.GuardedOrderTokenService;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * OSGi implementation of {@link JobProcessor} that processes jobs in token order,
 * grouped by topic.
 * 
 * <p>Each topic has its own {@link OrderedJobQueue} and single-threaded executor.
 * Jobs within a topic execute sequentially in token order. Different topics
 * process independently.</p>
 * 
 * <p>A configurable coalesce time allows jobs arriving from different machines
 * to be properly ordered before processing begins.</p>
 * 
 * <p>Jobs that exceed the configured timeout are cancelled to prevent
 * queue bottlenecking and high heap usage.</p>
 * 
 * <p>When persistence is enabled, jobs are stored in JCR and will be recovered
 * on JVM restart.</p>
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
    }

    @Reference
    private GuardedOrderTokenService tokenService;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private volatile JobPersistenceService persistenceService;

    // Map of job name -> GuardedJob for recovery
    private final Map<String, GuardedJob<?>> registeredJobs = new ConcurrentHashMap<>();

    @Reference(
        cardinality = ReferenceCardinality.MULTIPLE,
        policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY
    )
    protected void bindGuardedJob(GuardedJob<?> job) {
        registeredJobs.put(job.getName(), job);
        LOG.debug("Registered job for recovery: {}", job.getName());
    }

    protected void unbindGuardedJob(GuardedJob<?> job) {
        registeredJobs.remove(job.getName());
        LOG.debug("Unregistered job: {}", job.getName());
    }

    private final Map<String, TopicExecutor> topics = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private long coalesceTimeMs;
    private long jobTimeoutSeconds;
    private volatile boolean shutdown = false;

    @Activate
    protected void activate(Config config) {
        this.coalesceTimeMs = Math.max(0, config.coalesceTimeMs());
        this.jobTimeoutSeconds = Math.max(0, config.jobTimeoutSeconds());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("job-processor-scheduler");
            return t;
        });
        LOG.info("OrderedJobProcessor activated with coalesceTimeMs={}, jobTimeoutSeconds={}", 
            coalesceTimeMs, jobTimeoutSeconds);
        
        // Recover persisted jobs
        recoverPersistedJobs();
    }

    @Deactivate
    protected void deactivate() {
        shutdownNow();
    }

    private void recoverPersistedJobs() {
        JobPersistenceService persistence = this.persistenceService;
        if (persistence == null || !persistence.isEnabled()) {
            LOG.debug("Job persistence is not enabled, skipping recovery");
            return;
        }

        try {
            List<PersistedJob> persistedJobs = persistence.loadAll();
            if (persistedJobs.isEmpty()) {
                LOG.debug("No persisted jobs to recover");
                return;
            }

            LOG.info("Recovering {} persisted jobs...", persistedJobs.size());
            int recovered = 0;
            int failed = 0;

            for (PersistedJob persistedJob : persistedJobs) {
                GuardedJob<?> job = registeredJobs.get(persistedJob.getJobName());
                if (job == null) {
                    LOG.warn("Cannot recover job '{}' - no registered implementation found. Removing from persistence.",
                        persistedJob.getJobName());
                    try {
                        persistence.remove(persistedJob.getPersistenceId());
                    } catch (JobPersistenceException e) {
                        LOG.warn("Failed to remove orphaned job: {}", persistedJob.getPersistenceId(), e);
                    }
                    failed++;
                    continue;
                }

                try {
                    submitInternal(
                        persistedJob.getTopic(),
                        persistedJob.getToken(),
                        job,
                        persistedJob.getParameters(),
                        persistedJob.getPersistenceId() // Use existing persistence ID
                    );
                    recovered++;
                    LOG.debug("Recovered job: topic={}, jobName={}, id={}", 
                        persistedJob.getTopic(), persistedJob.getJobName(), persistedJob.getPersistenceId());
                } catch (Exception e) {
                    LOG.error("Failed to recover job: {}", persistedJob.getPersistenceId(), e);
                    failed++;
                }
            }

            LOG.info("Job recovery complete: {} recovered, {} failed", recovered, failed);

        } catch (JobPersistenceException e) {
            LOG.error("Failed to load persisted jobs", e);
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

        // Persist the job if persistence is enabled
        String persistenceId = null;
        JobPersistenceService persistence = this.persistenceService;
        if (persistence != null && persistence.isEnabled()) {
            try {
                persistenceId = persistence.persist(topic, token, job.getName(), parameters);
                LOG.debug("Persisted job: topic={}, jobName={}, id={}", topic, job.getName(), persistenceId);
            } catch (JobPersistenceException e) {
                LOG.warn("Failed to persist job (will continue without persistence): topic={}, jobName={}", 
                    topic, job.getName(), e);
            }
        }

        return submitInternal(topic, token, job, parameters, persistenceId);
    }

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> submitInternal(String topic, String token, GuardedJob<?> job, 
            Map<String, Object> parameters, String persistenceId) {
        TopicExecutor executor = topics.computeIfAbsent(topic, 
            t -> new TopicExecutor(t, tokenService, scheduler, coalesceTimeMs, jobTimeoutSeconds, persistenceService));
        return executor.submit(token, (GuardedJob<T>) job, parameters, persistenceId);
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
        TopicExecutor executor = topics.get(topic);
        return executor != null ? executor.getPendingCount() : 0;
    }

    @Override
    public int getTotalPendingCount() {
        return topics.values().stream()
            .mapToInt(TopicExecutor::getPendingCount)
            .sum();
    }

    @Override
    public void shutdown() {
        shutdown = true;
        topics.values().forEach(TopicExecutor::shutdown);
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Override
    public void shutdownNow() {
        shutdown = true;
        topics.values().forEach(TopicExecutor::shutdownNow);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * Executor for a single topic - runs jobs one at a time in token order.
     * Uses coalesce timing to allow jobs from different sources to be properly ordered.
     */
    private static class TopicExecutor {
        private static final Logger LOG = LoggerFactory.getLogger(TopicExecutor.class);

        private final String topic;
        private final OrderedJobQueue queue;
        private final ExecutorService executor;
        private final ScheduledExecutorService scheduler;
        private final long coalesceTimeMs;
        private final long jobTimeoutSeconds;
        private final JobPersistenceService persistenceService;
        private volatile long lastSubmitTime;
        private volatile boolean processing;
        private volatile ScheduledFuture<?> scheduledStart;

        TopicExecutor(String topic, GuardedOrderTokenService tokenService, ScheduledExecutorService scheduler, 
                      long coalesceTimeMs, long jobTimeoutSeconds, JobPersistenceService persistenceService) {
            this.topic = topic;
            this.queue = new OrderedJobQueue(tokenService);
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("topic-executor-" + topic);
                return t;
            });
            this.scheduler = scheduler;
            this.coalesceTimeMs = coalesceTimeMs;
            this.jobTimeoutSeconds = jobTimeoutSeconds;
            this.persistenceService = persistenceService;
            this.lastSubmitTime = 0;
            this.processing = false;
        }

        <T> CompletableFuture<T> submit(String token, GuardedJob<T> job, Map<String, Object> parameters, 
                String persistenceId) {
            CompletableFuture<T> future = queue.add(token, job, parameters, persistenceId);
            lastSubmitTime = System.currentTimeMillis();
            scheduleProcessing();
            return future;
        }

        private synchronized void scheduleProcessing() {
            if (processing) {
                return;
            }
            
            if (scheduledStart != null && !scheduledStart.isDone()) {
                scheduledStart.cancel(false);
            }
            
            scheduledStart = scheduler.schedule(this::startIfReady, coalesceTimeMs, TimeUnit.MILLISECONDS);
        }

        private synchronized void startIfReady() {
            if (processing || queue.isEmpty()) {
                return;
            }
            
            long elapsed = System.currentTimeMillis() - lastSubmitTime;
            if (elapsed < coalesceTimeMs) {
                scheduledStart = scheduler.schedule(this::startIfReady, coalesceTimeMs - elapsed, TimeUnit.MILLISECONDS);
                return;
            }
            
            processing = true;
            executor.submit(this::processAll);
        }

        private void processAll() {
            OrderedJobQueue.JobEntry<?> entry;
            while ((entry = queue.poll()) != null) {
                executeWithTimeout(entry);
                
                // Remove from persistence after execution (success or failure)
                removeFromPersistence(entry.getPersistenceId());
            }
            
            synchronized (this) {
                processing = false;
                if (!queue.isEmpty()) {
                    scheduleProcessing();
                }
            }
        }

        private void removeFromPersistence(String persistenceId) {
            if (persistenceId == null || persistenceService == null) {
                return;
            }
            
            try {
                persistenceService.remove(persistenceId);
                LOG.debug("Removed completed job from persistence: {}", persistenceId);
            } catch (JobPersistenceException e) {
                LOG.warn("Failed to remove job from persistence: {}", persistenceId, e);
            }
        }

        private void executeWithTimeout(OrderedJobQueue.JobEntry<?> entry) {
            if (jobTimeoutSeconds <= 0) {
                // Timeout disabled, execute directly
                entry.execute();
                return;
            }

            String jobName = entry.getJob().getName();
            long startTime = System.currentTimeMillis();
            
            // Create a separate thread for job execution so we can interrupt it
            ExecutorService jobExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("job-runner-" + jobName);
                return t;
            });

            Future<?> jobFuture = jobExecutor.submit(entry::execute);

            try {
                jobFuture.get(jobTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
                LOG.warn("Job '{}' in topic '{}' cancelled after {} seconds (timeout: {}s). " +
                         "This may indicate a long-running or stuck job that could cause queue bottlenecking and high heap usage.",
                         jobName, topic, elapsedSeconds, jobTimeoutSeconds);
                
                // Complete the future exceptionally FIRST, before cancelling
                // This ensures the TimeoutException is set before any InterruptedException from cancellation
                boolean completed = entry.getFuture().completeExceptionally(
                    new TimeoutException("Job '" + jobName + "' cancelled after exceeding timeout of " + 
                                         jobTimeoutSeconds + " seconds"));
                
                if (completed) {
                    LOG.debug("Job '{}' future completed with TimeoutException", jobName);
                }
                
                // Now cancel the job and interrupt the thread
                jobFuture.cancel(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Job '{}' in topic '{}' was interrupted", jobName, topic);
                entry.getFuture().completeExceptionally(e);
            } catch (ExecutionException e) {
                // Job threw an exception - this is already handled by entry.execute()
                // but we log it here for visibility
                Throwable cause = e.getCause();
                String errorMessage = cause != null ? cause.toString() : e.toString();
                LOG.debug("Job '{}' in topic '{}' threw an exception: {}", jobName, topic, errorMessage);
            } finally {
                jobExecutor.shutdownNow();
            }
        }

        int getPendingCount() {
            return queue.size();
        }

        void shutdown() {
            executor.shutdown();
        }

        void shutdownNow() {
            executor.shutdownNow();
        }
    }
}
