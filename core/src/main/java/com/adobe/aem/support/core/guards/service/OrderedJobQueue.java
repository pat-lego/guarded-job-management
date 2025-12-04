package com.adobe.aem.support.core.guards.service;

import com.adobe.aem.support.core.guards.token.GuardedOrderTokenService;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * A thread-safe queue that maintains jobs in token order.
 * 
 * <p>Jobs are stored sorted by their token's timestamp. Adding and removing
 * jobs are mutually exclusive operations.</p>
 */
public class OrderedJobQueue {

    private final GuardedOrderTokenService tokenService;
    private final TreeMap<Long, JobEntry<?>> jobs;

    public OrderedJobQueue(GuardedOrderTokenService tokenService) {
        this.tokenService = tokenService;
        this.jobs = new TreeMap<>();
    }

    /**
     * Adds a job to the queue in its proper order based on token.
     *
     * @param token      the guarded order token
     * @param job        the job to execute
     * @param parameters the parameters to pass to the job
     * @param <T>        the job result type
     * @return a future that will contain the job result
     * @throws IllegalArgumentException if token is invalid
     */
    public synchronized <T> CompletableFuture<T> add(String token, GuardedJob<T> job, Map<String, Object> parameters) {
        if (!tokenService.isValid(token)) {
            throw new IllegalArgumentException("Invalid or tampered token");
        }
        long timestamp = tokenService.extractTimestamp(token);
        CompletableFuture<T> future = new CompletableFuture<>();
        jobs.put(timestamp, new JobEntry<>(job, parameters, future));
        return future;
    }

    /**
     * Removes and returns the next job to process (smallest token/earliest time).
     *
     * @return the next job entry, or null if queue is empty
     */
    public synchronized JobEntry<?> poll() {
        Map.Entry<Long, JobEntry<?>> first = jobs.pollFirstEntry();
        return first != null ? first.getValue() : null;
    }

    /**
     * Returns the number of jobs in the queue.
     */
    public synchronized int size() {
        return jobs.size();
    }

    /**
     * Checks if the queue is empty.
     */
    public synchronized boolean isEmpty() {
        return jobs.isEmpty();
    }

    /**
     * Entry holding a job, its parameters, and the completion future.
     */
    public static class JobEntry<T> {
        private final GuardedJob<T> job;
        private final Map<String, Object> parameters;
        private final CompletableFuture<T> future;

        JobEntry(GuardedJob<T> job, Map<String, Object> parameters, CompletableFuture<T> future) {
            this.job = job;
            this.parameters = parameters;
            this.future = future;
        }

        public GuardedJob<T> getJob() {
            return job;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }

        public CompletableFuture<T> getFuture() {
            return future;
        }

        /**
         * Executes the job and completes the future.
         */
        public void execute() {
            try {
                T result = job.execute(parameters);
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }
    }
}
