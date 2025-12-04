package com.adobe.aem.support.core.guards.jobs;

import com.adobe.aem.support.core.guards.service.GuardedJob;
import com.adobe.aem.support.core.guards.servlets.JobSubmitServlet;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationOptions;
import com.day.cq.replication.Replicator;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Session;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A GuardedJob that replicates content from Author to Publisher using the AEM Replicator API.
 * 
 * <p>This job uses <b>synchronous replication</b> - it triggers replication in {@link #execute(Map)} 
 * and blocks until the replication transport completes. This ensures the job finishes only when
 * the content has been delivered to the replication queue on the target.</p>
 * 
 * <p><b>Important:</b> This job requires the user's ResourceResolver to be passed in parameters
 * via the {@link JobSubmitServlet#PARAM_RESOURCE_RESOLVER} key. The job will fail if the resolver
 * is not present, as it cannot assume a service user has the required replication permissions.</p>
 * 
 * <h3>Parameters:</h3>
 * <ul>
 *   <li><b>paths</b> (required) - Array or comma-separated list of paths to replicate</li>
 *   <li><b>action</b> (optional) - Replication action: "ACTIVATE" (default), "DEACTIVATE", or "DELETE"</li>
 *   <li><b>_resourceResolver</b> (required, injected by servlet) - The user's ResourceResolver</li>
 * </ul>
 * 
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * POST /bin/guards/job.submit.json
 * {
 *     "topic": "replication",
 *     "jobName": "replicate",
 *     "parameters": {
 *         "paths": ["/content/mysite/page1", "/content/mysite/page2"],
 *         "action": "ACTIVATE"
 *     }
 * }
 * }</pre>
 * 
 * @see <a href="https://developer.adobe.com/experience-manager/reference-materials/6-5/javadoc/com/day/cq/replication/Replicator.html">
 *      AEM Replicator API</a>
 */
@Component(service = GuardedJob.class)
public class ReplicationGuardedJob implements GuardedJob<ReplicationGuardedJob.ReplicationResult> {

    private static final Logger LOG = LoggerFactory.getLogger(ReplicationGuardedJob.class);

    private static final String PARAM_PATHS = "paths";
    private static final String PARAM_ACTION = "action";
    private static final String SERVICE_USER = "guarded-job-service";

    @Reference
    private Replicator replicator;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    public String getName() {
        return "replicate";
    }

    @Override
    public ReplicationResult execute(Map<String, Object> parameters) throws Exception {
        // Get ResourceResolver - either from parameters (direct call) or via impersonation (from JCR)
        ResourceResolver resolver = getResourceResolver(parameters);
        boolean ownResolver = !(parameters.get(JobSubmitServlet.PARAM_RESOURCE_RESOLVER) instanceof ResourceResolver);

        try {
            // Parse paths
            String[] paths = parsePaths(parameters);
            if (paths == null || paths.length == 0) {
                throw new IllegalArgumentException("Missing required parameter: paths");
            }

            // Parse action (default: ACTIVATE)
            ReplicationActionType actionType = parseAction(parameters);

            String requestId = generateRequestId();

            LOG.info("Initiating synchronous replication: requestId={}, action={}, paths={}",
                    requestId, actionType, Arrays.toString(paths));

            Session session = resolver.adaptTo(Session.class);
            if (session == null) {
                throw new IllegalStateException("Could not obtain JCR session from ResourceResolver");
            }

            // Configure replication options - always synchronous
            ReplicationOptions options = new ReplicationOptions();
            options.setSynchronous(true);

            try {
                // Replicate in batches of up to 10 paths (more efficient than one-by-one, but avoids overhead of too many at once)
                int batchSize = 10;
                for (int i = 0; i < paths.length; i += batchSize) {
                    int end = Math.min(i + batchSize, paths.length);
                    String[] batch = Arrays.copyOfRange(paths, i, end);
                    LOG.debug("Replicating batch of {} paths: {}", batch.length, Arrays.toString(batch));
                    replicator.replicate(session, actionType, batch, options);
                }

                LOG.info("Replication completed: requestId={}, paths={}", requestId, Arrays.toString(paths));

                return new ReplicationResult(
                        requestId,
                        "COMPLETED",
                        paths,
                        "Replication completed successfully"
                );

            } catch (ReplicationException e) {
                LOG.error("Replication failed: requestId={}, error={}", requestId, e.getMessage(), e);
                throw new RuntimeException("Replication failed: " + e.getMessage(), e);
            }
        } finally {
            // Close resolver if we created it via impersonation
            if (ownResolver && resolver != null) {
                resolver.close();
            }
        }
    }

    /**
     * Gets a ResourceResolver for replication. Tries in order:
     * 1. Direct ResourceResolver from parameters (when called directly from servlet)
     * 2. Impersonation using the service user and the submittedBy user ID
     */
    private ResourceResolver getResourceResolver(Map<String, Object> parameters) throws LoginException {
        // First, try to get the resolver directly from parameters (when called from servlet)
        Object resolverObj = parameters.get(JobSubmitServlet.PARAM_RESOURCE_RESOLVER);
        if (resolverObj instanceof ResourceResolver) {
            LOG.debug("Using ResourceResolver from parameters");
            return (ResourceResolver) resolverObj;
        }

        // Otherwise, use impersonation with the service user
        String submittedBy = (String) parameters.get(JobSubmitServlet.PARAM_SUBMITTED_BY);
        if (submittedBy == null || submittedBy.isEmpty()) {
            submittedBy = "admin"; // Fallback to admin if no user specified
            LOG.warn("No submittedBy user found, falling back to: {}", submittedBy);
        }

        LOG.debug("Creating impersonated resolver for user: {}", submittedBy);

        Map<String, Object> authInfo = new HashMap<>();
        authInfo.put(ResourceResolverFactory.SUBSERVICE, SERVICE_USER);
        authInfo.put(ResourceResolverFactory.USER_IMPERSONATION, submittedBy);
        
        return resolverFactory.getServiceResourceResolver(authInfo);
    }

    // === Helper Methods ===

    private String[] parsePaths(Map<String, Object> parameters) {
        Object pathsObj = parameters.get(PARAM_PATHS);
        if (pathsObj == null) {
            return null;
        }

        if (pathsObj instanceof String[]) {
            return (String[]) pathsObj;
        } else if (pathsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> pathList = (List<String>) pathsObj;
            return pathList.toArray(new String[0]);
        } else if (pathsObj instanceof String) {
            String pathStr = (String) pathsObj;
            // Support comma-separated paths
            return Arrays.stream(pathStr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
        }

        throw new IllegalArgumentException("Invalid paths parameter type: " + pathsObj.getClass());
    }

    private ReplicationActionType parseAction(Map<String, Object> parameters) {
        Object actionObj = parameters.get(PARAM_ACTION);
        if (actionObj == null) {
            return ReplicationActionType.ACTIVATE;
        }

        String action = actionObj.toString().toUpperCase();
        switch (action) {
            case "ACTIVATE":
                return ReplicationActionType.ACTIVATE;
            case "DEACTIVATE":
                return ReplicationActionType.DEACTIVATE;
            case "DELETE":
                return ReplicationActionType.DELETE;
            default:
                LOG.warn("Unknown action '{}', defaulting to ACTIVATE", action);
                return ReplicationActionType.ACTIVATE;
        }
    }

    private String generateRequestId() {
        return "repl-" + System.currentTimeMillis() + "-" + 
               Long.toHexString(Double.doubleToLongBits(Math.random()));
    }

    // === Result Class ===

    /**
     * Result object returned by the replication job.
     */
    public static class ReplicationResult {
        private final String requestId;
        private final String state;
        private final String[] paths;
        private final String message;

        public ReplicationResult(String requestId, String state, String[] paths, String message) {
            this.requestId = requestId;
            this.state = state;
            this.paths = paths;
            this.message = message;
        }

        public String getRequestId() { return requestId; }
        public String getState() { return state; }
        public String[] getPaths() { return paths; }
        public String getMessage() { return message; }

        @Override
        public String toString() {
            return String.format("ReplicationResult{requestId='%s', state='%s', paths=%s, message='%s'}",
                    requestId, state, Arrays.toString(paths), message);
        }
    }
}
