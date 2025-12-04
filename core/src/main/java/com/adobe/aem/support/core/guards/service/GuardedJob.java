package com.adobe.aem.support.core.guards.service;

import java.util.Map;

/**
 * Represents a named job that can be submitted for ordered processing.
 * 
 * <p>Implementations are OSGi services discovered at runtime. Each implementation
 * must have a unique name - duplicate names will cause servlet startup failure.</p>
 * 
 * <p>Example implementation:
 * <pre>{@code
 * @Component(service = GuardedJob.class)
 * public class PublishPageJob implements GuardedJob<String> {
 *     
 *     @Reference
 *     private PageManager pageManager;
 *     
 *     @Override
 *     public String getName() {
 *         return "publish-page";
 *     }
 *     
 *     @Override
 *     public String execute(Map<String, Object> parameters) throws Exception {
 *         String pagePath = (String) parameters.get("pagePath");
 *         pageManager.publish(pagePath);
 *         return "Published: " + pagePath;
 *     }
 *     
 *     @Override
 *     public long getTimeoutSeconds() {
 *         return 120; // Allow 2 minutes for publishing
 *     }
 * }
 * }</pre>
 * </p>
 *
 * @param <T> the type of result produced by this job
 */
public interface GuardedJob<T> {

    /**
     * Returns the unique name for this job.
     * 
     * <p>This name is used to identify the job type in HTTP requests.
     * Must be unique across all registered jobs.</p>
     *
     * @return the unique job name
     */
    String getName();

    /**
     * Executes the job with the given parameters.
     * 
     * <p>This method is called by the {@link JobProcessor} when it's time
     * to process this job according to its ordering token.</p>
     * 
     * <p>For synchronous jobs (default), this method should complete the full
     * job execution before returning. For asynchronous jobs (where {@link #isAsync()}
     * returns true), this method may return after initiating the async operation,
     * and the processor will poll {@link #isComplete(Map)} to determine completion.</p>
     *
     * @param parameters the parameters for the job (from HTTP request)
     * @return the result of the job execution
     * @throws Exception if the job fails during execution
     */
    T execute(Map<String, Object> parameters) throws Exception;

    /**
     * Indicates whether this job is asynchronous.
     * 
     * <p>For synchronous jobs (default), {@link #execute(Map)} is expected to block
     * until the job completes. For asynchronous jobs, {@code execute()} may return
     * immediately after initiating the async operation, and the processor will
     * poll {@link #isComplete(Map)} to determine when the job has finished.</p>
     *
     * @return true if this job is asynchronous, false otherwise (default)
     */
    default boolean isAsync() {
        return false;
    }

    /**
     * Checks whether an asynchronous job has completed.
     * 
     * <p>This method is only called for jobs where {@link #isAsync()} returns true.
     * The processor will poll this method periodically after {@link #execute(Map)}
     * returns to determine when the async work has finished.</p>
     * 
     * <p>The same parameters passed to {@code execute()} are provided here,
     * allowing the implementation to look up the status of the initiated operation.</p>
     *
     * @param parameters the same parameters that were passed to {@link #execute(Map)}
     * @return true if the async job has completed, false if still in progress
     * @throws Exception if checking completion status fails
     */
    default boolean isComplete(Map<String, Object> parameters) throws Exception {
        return true; // Synchronous jobs are always complete after execute()
    }

    /**
     * Returns the polling interval in milliseconds for checking async job completion.
     * 
     * <p>Override this method to customize how frequently {@link #isComplete(Map)}
     * is called when waiting for an async job to finish. Only applies when
     * {@link #isAsync()} returns true.</p>
     *
     * @return the polling interval in milliseconds, defaults to 1000ms (1 second)
     */
    default long getAsyncPollingIntervalMs() {
        return 1000;
    }

    /**
     * Returns the timeout in seconds for this job.
     * 
     * <p>Override this method to specify a custom timeout for this job type.
     * Return a positive value to set a specific timeout, or return -1 (default)
     * to use the global timeout configured in {@code OrderedJobProcessor}.</p>
     * 
     * <p>Returning 0 disables timeout for this job (use with caution).</p>
     * 
     * <p>For async jobs, this timeout covers the total time from when {@link #execute(Map)}
     * is called until {@link #isComplete(Map)} returns true.</p>
     *
     * @return the timeout in seconds, 0 to disable timeout, or -1 to use the global default
     */
    default long getTimeoutSeconds() {
        return -1; // Use global default
    }
}
