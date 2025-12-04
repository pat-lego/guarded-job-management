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
     * @param parameters the parameters for the job (from HTTP request)
     * @return the result of the job execution
     * @throws Exception if the job fails during execution
     */
    T execute(Map<String, Object> parameters) throws Exception;


    /**
     * Returns the timeout in seconds for this job.
     * 
     * <p>Override this method to specify a custom timeout for this job type.
     * Return a positive value to set a specific timeout, or return -1 (default)
     * to use the global timeout configured in {@code OrderedJobProcessor}.</p>
     * 
     * <p>Returning 0 disables timeout for this job (use with caution).</p>
     *
     * @return the timeout in seconds, 0 to disable timeout, or -1 to use the global default
     */
    default long getTimeoutSeconds() {
        return -1; // Use global default
    }
}
