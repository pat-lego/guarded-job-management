package com.adobe.aem.support.core.guards.service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service for processing jobs in token order, grouped by topic.
 * 
 * <p>Jobs are grouped by topic, and within each topic they are processed
 * in the order determined by their guarded tokens. Jobs in different topics
 * are processed independently and do not affect each other's ordering.</p>
 * 
 * <p>Tokens should be obtained from {@link com.adobe.aem.support.core.guards.token.GuardedOrderTokenService}
 * before submitting jobs. The token's creation time determines execution order,
 * not the arrival time of the job submission.</p>
 */
public interface JobProcessor {

    /**
     * Submits a job for ordered processing within a topic.
     * 
     * <p>The job will be executed in order based on the token's timestamp,
     * not based on when this method is called. Jobs with earlier tokens
     * are processed before jobs with later tokens.</p>
     *
     * @param <T>        the type of result produced by the job
     * @param topic      the topic this job belongs to (must not be null or empty)
     * @param token      the guarded order token (from GuardedOrderTokenService)
     * @param job        the job to execute
     * @param parameters the parameters to pass to the job
     * @return a CompletableFuture that completes with the job result
     * @throws IllegalArgumentException if topic is null/empty, token is invalid, or job is null
     */
    <T> CompletableFuture<T> submit(String topic, String token, GuardedJob<T> job, Map<String, Object> parameters);

    /**
     * Returns the number of pending jobs for a specific topic.
     *
     * @param topic the topic to check
     * @return the number of jobs waiting to be processed in that topic
     */
    int getPendingCount(String topic);

    /**
     * Returns the total number of pending jobs across all topics.
     *
     * @return the total number of jobs waiting to be processed
     */
    int getTotalPendingCount();

    /**
     * Shuts down the processor gracefully.
     * 
     * <p>No new jobs will be accepted after shutdown. Already submitted jobs
     * will continue to be processed until complete.</p>
     */
    void shutdown();

    /**
     * Shuts down the processor immediately.
     * 
     * <p>No new jobs will be accepted. Pending jobs that have not started
     * will be cancelled.</p>
     */
    void shutdownNow();

    /**
     * Checks if the processor has been shut down.
     *
     * @return true if shutdown has been initiated
     */
    boolean isShutdown();
}
