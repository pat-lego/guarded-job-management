package com.adobe.aem.support.core.guards.persistence.impl;

import com.adobe.aem.support.core.guards.cluster.ClusterLeaderService;
import com.adobe.aem.support.core.guards.persistence.JobPersistenceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sling.api.resource.*;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.query.Query;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * JCR-based implementation of {@link JobPersistenceService}.
 * 
 * <p>This service is always enabled and persists all jobs to JCR for durability.
 * Jobs are stored with indexed timestamp fields to enable efficient querying.
 * Only the cluster leader can recover and process persisted jobs.</p>
 * 
 * <p>Storage structure:
 * <pre>
 * /var/guarded-jobs/
 *   {sling-id}/
 *     {year}/
 *       {month}/
 *         {day}/
 *           {job-id}/
 *             - jcr:primaryType = nt:unstructured
 *             - topic = "my-topic"
 *             - tokenTimestamp = 1733325600001234567 (Long, indexed for queries)
 *             - tokenSignature = "kX9mQz..." (String)
 *             - jobName = "echo"
 *             - persistedAt = 1733325600000
 *             - parameters (binary JSON blob)
 * </pre>
 * </p>
 * 
 * <p>The token is split into timestamp and signature to enable JCR queries
 * that return jobs ordered by timestamp with a limit, avoiding loading all jobs
 * into memory.</p>
 */
@Component(service = JobPersistenceService.class, immediate = true)
public class JcrJobPersistenceService implements JobPersistenceService {

    private static final Logger LOG = LoggerFactory.getLogger(JcrJobPersistenceService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    // Mixin and property names (gjm = guarded-job-management namespace)
    private static final String MIXIN_GUARDED_JOB = "gjm:GuardedJob";
    private static final String PROP_TOPIC = "gjm:topic";
    private static final String PROP_TOKEN_TIMESTAMP = "gjm:tokenTimestamp";
    private static final String PROP_TOKEN_SIGNATURE = "gjm:tokenSignature";
    private static final String PROP_JOB_NAME = "gjm:jobName";
    private static final String PROP_SUBMITTED_BY = "gjm:submittedBy";
    private static final String PROP_PERSISTED_AT = "persistedAt";
    private static final String PROP_PARAMETERS = "parameters";
    private static final String NT_UNSTRUCTURED = "nt:unstructured";
    private static final String JCR_MIXIN_TYPES = "jcr:mixinTypes";

    private static final String STORAGE_PATH = "/var/guarded-jobs";
    private static final String SERVICE_USER = "guarded-job-service";
    
    /** Maximum number of jobs to load per query. Prevents memory issues with large backlogs. */
    private static final int MAX_JOBS_PER_QUERY = 100;

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private ClusterLeaderService clusterLeaderService;

    @Activate
    protected void activate() {
        LOG.info("JcrJobPersistenceService activated: slingId={}, storagePath={}, maxJobsPerQuery={}", 
            clusterLeaderService.getSlingId(), STORAGE_PATH, MAX_JOBS_PER_QUERY);
        
        ensureStoragePathExists();
    }

    @Override
    public boolean isEnabled() {
        return true; // Always enabled
    }

    @Override
    public String persist(String topic, String token, String jobName, String submittedBy, Map<String, Object> parameters) 
            throws JobPersistenceException {

        String slingId = clusterLeaderService.getSlingId();
        String jobId = UUID.randomUUID().toString();
        String datePath = buildDatePath();
        String jobPath = STORAGE_PATH + "/" + slingId + "/" + datePath + "/" + jobId;

        // Split token into timestamp and signature for indexed querying
        long tokenTimestamp; 
        String tokenSignature;
        try {
            String[] parts = token.split("\\.", 2);
            tokenTimestamp = Long.parseLong(parts[0]);
            tokenSignature = parts.length > 1 ? parts[1] : "";
        } catch (Exception e) {
            throw new JobPersistenceException("Invalid token format: " + token, e);
        }

        try (ResourceResolver resolver = getServiceResolver()) {
            // Ensure date-based folder structure exists
            String parentPath = STORAGE_PATH + "/" + slingId + "/" + datePath;
            ensurePathExists(resolver, parentPath);

            // Create job node
            Resource parentResource = resolver.getResource(parentPath);
            if (parentResource == null) {
                throw new JobPersistenceException("Failed to access parent path: " + parentPath);
            }

            Map<String, Object> properties = new HashMap<>();
            properties.put(ResourceResolver.PROPERTY_RESOURCE_TYPE, NT_UNSTRUCTURED);
            properties.put(JCR_MIXIN_TYPES, new String[] { MIXIN_GUARDED_JOB });
            properties.put(PROP_TOPIC, topic);
            properties.put(PROP_TOKEN_TIMESTAMP, tokenTimestamp);
            properties.put(PROP_TOKEN_SIGNATURE, tokenSignature);
            properties.put(PROP_JOB_NAME, jobName);
            properties.put(PROP_SUBMITTED_BY, submittedBy != null ? submittedBy : "unknown");
            properties.put(PROP_PERSISTED_AT, System.currentTimeMillis());
            
            // Store parameters as JSON blob
            String parametersJson = serializeParameters(parameters);
            InputStream parametersStream = new ByteArrayInputStream(
                parametersJson.getBytes(StandardCharsets.UTF_8));
            properties.put(PROP_PARAMETERS, parametersStream);

            resolver.create(parentResource, jobId, properties);
            resolver.commit();

            LOG.debug("Persisted job: topic={}, jobName={}, submittedBy={}, timestamp={}, path={}", 
                topic, jobName, submittedBy, tokenTimestamp, jobPath);
            return jobPath;

        } catch (LoginException e) {
            throw new JobPersistenceException("Failed to get service resolver", e);
        } catch (PersistenceException e) {
            throw new JobPersistenceException("Failed to persist job: " + jobPath, e);
        }
    }

    /**
     * Builds the date path: year/month/day (e.g., "2024/12/04")
     */
    private String buildDatePath() {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        return String.format("%d/%02d/%02d", today.getYear(), today.getMonthValue(), today.getDayOfMonth());
    }

    @Override
    public void remove(String persistenceId) throws JobPersistenceException {
        if (persistenceId == null) {
            return;
        }

        try (ResourceResolver resolver = getServiceResolver()) {
            Resource jobResource = resolver.getResource(persistenceId);
            if (jobResource != null) {
                resolver.delete(jobResource);
                resolver.commit();
                LOG.debug("Removed persisted job: {}", persistenceId);
            }
        } catch (LoginException e) {
            throw new JobPersistenceException("Failed to get service resolver", e);
        } catch (PersistenceException e) {
            throw new JobPersistenceException("Failed to remove job: " + persistenceId, e);
        }
    }

    @Override
    public List<PersistedJob> loadAll() throws JobPersistenceException {
        List<PersistedJob> jobs = new ArrayList<>();

        if (!clusterLeaderService.isLeader()) {
            LOG.debug("Not the cluster leader, skipping job loading");
            return jobs;
        }

        try (ResourceResolver resolver = getServiceResolver()) {
            Resource storageResource = resolver.getResource(STORAGE_PATH);
            if (storageResource == null) {
                LOG.debug("Storage path does not exist: {}", STORAGE_PATH);
                return jobs;
            }

            // Try JCR query first (efficient for production with large job counts)
            jobs = loadJobsViaQuery(resolver);
            
            // Fallback to traversal if query returned no results
            // This handles mock environments and edge cases
            if (jobs.isEmpty()) {
                jobs = loadJobsViaTraversal(storageResource);
            }
            
            return jobs;

        } catch (LoginException e) {
            throw new JobPersistenceException("Failed to get service resolver", e);
        }
    }

    @Override
    public List<PersistedJob> loadByTopic(String topic, int limit) throws JobPersistenceException {
        List<PersistedJob> jobs = new ArrayList<>();

        if (topic == null || topic.trim().isEmpty()) {
            return jobs;
        }

        int effectiveLimit = Math.min(limit, MAX_JOBS_PER_QUERY);
        if (effectiveLimit <= 0) {
            effectiveLimit = MAX_JOBS_PER_QUERY;
        }

        try (ResourceResolver resolver = getServiceResolver()) {
            Resource storageResource = resolver.getResource(STORAGE_PATH);
            if (storageResource == null) {
                LOG.debug("Storage path does not exist: {}", STORAGE_PATH);
                return jobs;
            }

            // Try JCR query first (efficient for production with proper indexing)
            jobs = loadJobsByTopicViaQuery(resolver, topic, effectiveLimit);
            
            // Fallback to traversal if query returned no results
            if (jobs.isEmpty()) {
                jobs = loadJobsByTopicViaTraversal(storageResource, topic, effectiveLimit);
            }
            
            return jobs;

        } catch (LoginException e) {
            throw new JobPersistenceException("Failed to get service resolver", e);
        }
    }

    /**
     * Loads jobs for a specific topic using JCR SQL2 query.
     */
    private List<PersistedJob> loadJobsByTopicViaQuery(ResourceResolver resolver, String topic, int limit) {
        List<PersistedJob> jobs = new ArrayList<>();
        
        try {
            // Query by mixin type and topic, ordered by timestamp
            String query = String.format(
                "SELECT * FROM [%s] AS job " +
                "WHERE ISDESCENDANTNODE(job, '%s') " +
                "AND job.[%s] = '%s' " +
                "ORDER BY job.[%s] ASC " +
                "OPTION(LIMIT %d)",
                MIXIN_GUARDED_JOB, STORAGE_PATH, PROP_TOPIC, topic.replace("'", "''"), 
                PROP_TOKEN_TIMESTAMP, limit
            );

            Iterator<Resource> results = resolver.findResources(query, Query.JCR_SQL2);
            
            while (results.hasNext()) {
                Resource jobResource = results.next();
                try {
                    PersistedJob job = loadJob(jobResource);
                    if (job != null) {
                        jobs.add(job);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to load persisted job: {}", jobResource.getPath(), e);
                }
            }

            if (!jobs.isEmpty()) {
                LOG.debug("Loaded {} jobs for topic '{}' via query (limit: {})", jobs.size(), topic, limit);
            }
        } catch (Exception e) {
            LOG.debug("Query failed for topic '{}', will use traversal fallback: {}", topic, e.getMessage());
        }
        
        return jobs;
    }

    /**
     * Loads jobs for a specific topic via tree traversal - fallback for environments without query support.
     */
    private List<PersistedJob> loadJobsByTopicViaTraversal(Resource storageResource, String topic, int limit) {
        List<PersistedJob> jobs = new ArrayList<>();
        
        // Traverse: /var/guarded-jobs/{sling-id}/{year}/{month}/{day}/{job-id}
        for (Resource slingIdResource : storageResource.getChildren()) {
            for (Resource yearResource : slingIdResource.getChildren()) {
                for (Resource monthResource : yearResource.getChildren()) {
                    for (Resource dayResource : monthResource.getChildren()) {
                        for (Resource jobResource : dayResource.getChildren()) {
                            try {
                                PersistedJob job = loadJob(jobResource);
                                if (job != null && topic.equals(job.getTopic())) {
                                    jobs.add(job);
                                }
                            } catch (Exception e) {
                                LOG.warn("Failed to load persisted job: {}", jobResource.getPath(), e);
                            }
                        }
                    }
                }
            }
        }

        // Sort by timestamp and limit
        jobs.sort(Comparator.comparingLong(job -> {
            try {
                String[] parts = job.getToken().split("\\.", 2);
                return Long.parseLong(parts[0]);
            } catch (Exception e) {
                return 0L;
            }
        }));
        
        if (jobs.size() > limit) {
            jobs = jobs.subList(0, limit);
        }

        if (!jobs.isEmpty()) {
            LOG.debug("Loaded {} jobs for topic '{}' via traversal (limit: {})", jobs.size(), topic, limit);
        }
        
        return jobs;
    }

    /**
     * Loads jobs using JCR SQL2 query - efficient for production with proper indexing.
     * Returns jobs ordered by tokenTimestamp with a limit.
     * 
     * @see <a href="https://jackrabbit.apache.org/oak/docs/query/query-engine.html#query-option-offset-limit">Oak Query Options</a>
     */
    private List<PersistedJob> loadJobsViaQuery(ResourceResolver resolver) {
        List<PersistedJob> jobs = new ArrayList<>();
        
        try {
            // Use OPTION(LIMIT x) for efficient query limiting in Oak
            // Query by mixin type for better index utilization
            String query = String.format(
                "SELECT * FROM [%s] AS job " +
                "WHERE ISDESCENDANTNODE(job, '%s') " +
                "ORDER BY job.[%s] ASC " +
                "OPTION(LIMIT %d)",
                MIXIN_GUARDED_JOB, STORAGE_PATH, PROP_TOKEN_TIMESTAMP, MAX_JOBS_PER_QUERY
            );

            Iterator<Resource> results = resolver.findResources(query, Query.JCR_SQL2);
            
            while (results.hasNext()) {
                Resource jobResource = results.next();
                try {
                    PersistedJob job = loadJob(jobResource);
                    if (job != null) {
                        jobs.add(job);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to load persisted job: {}", jobResource.getPath(), e);
                }
            }

            if (!jobs.isEmpty()) {
                LOG.debug("Loaded {} jobs via query (limit: {})", jobs.size(), MAX_JOBS_PER_QUERY);
            }
        } catch (Exception e) {
            LOG.debug("Query failed, will use traversal fallback: {}", e.getMessage());
        }
        
        return jobs;
    }

    /**
     * Loads jobs via tree traversal - fallback for environments without query support.
     * Returns jobs sorted by tokenTimestamp with a limit.
     */
    private List<PersistedJob> loadJobsViaTraversal(Resource storageResource) {
        List<PersistedJob> jobs = new ArrayList<>();
        
        // Traverse: /var/guarded-jobs/{sling-id}/{year}/{month}/{day}/{job-id}
        for (Resource slingIdResource : storageResource.getChildren()) {
            for (Resource yearResource : slingIdResource.getChildren()) {
                for (Resource monthResource : yearResource.getChildren()) {
                    for (Resource dayResource : monthResource.getChildren()) {
                        for (Resource jobResource : dayResource.getChildren()) {
                            try {
                                PersistedJob job = loadJob(jobResource);
                                if (job != null) {
                                    jobs.add(job);
                                }
                            } catch (Exception e) {
                                LOG.warn("Failed to load persisted job: {}", jobResource.getPath(), e);
                            }
                        }
                    }
                }
            }
        }

        // Sort by timestamp and limit
        jobs.sort(Comparator.comparingLong(job -> {
            try {
                String[] parts = job.getToken().split("\\.", 2);
                return Long.parseLong(parts[0]);
            } catch (Exception e) {
                return 0L;
            }
        }));
        
        if (jobs.size() > MAX_JOBS_PER_QUERY) {
            jobs = jobs.subList(0, MAX_JOBS_PER_QUERY);
        }

        if (!jobs.isEmpty()) {
            LOG.debug("Loaded {} jobs via traversal (limit: {})", jobs.size(), MAX_JOBS_PER_QUERY);
        }
        
        return jobs;
    }

    private PersistedJob loadJob(Resource jobResource) {
        ValueMap properties = jobResource.getValueMap();
        
        String topic = properties.get(PROP_TOPIC, String.class);
        Long tokenTimestamp = properties.get(PROP_TOKEN_TIMESTAMP, Long.class);
        String tokenSignature = properties.get(PROP_TOKEN_SIGNATURE, String.class);
        String jobName = properties.get(PROP_JOB_NAME, String.class);
        String submittedBy = properties.get(PROP_SUBMITTED_BY, String.class);
        Long persistedAt = properties.get(PROP_PERSISTED_AT, Long.class);
        
        if (topic == null || tokenTimestamp == null || jobName == null) {
            LOG.warn("Invalid persisted job (missing required properties): {}", jobResource.getPath());
            return null;
        }

        // Reconstruct the token from timestamp and signature
        String token = tokenTimestamp + "." + (tokenSignature != null ? tokenSignature : "");

        // Load parameters from binary property
        Map<String, Object> parameters = new HashMap<>();
        InputStream parametersStream = properties.get(PROP_PARAMETERS, InputStream.class);
        if (parametersStream != null) {
            try {
                String json = new String(parametersStream.readAllBytes(), StandardCharsets.UTF_8);
                parameters = deserializeParameters(json);
            } catch (Exception e) {
                LOG.warn("Failed to deserialize parameters for job: {}", jobResource.getPath(), e);
            }
        }

        return new PersistedJob(
            jobResource.getPath(),
            topic,
            token,
            jobName,
            submittedBy != null ? submittedBy : "unknown",
            parameters,
            persistedAt != null ? persistedAt : 0
        );
    }

    private ResourceResolver getServiceResolver() throws LoginException {
        Map<String, Object> authInfo = new HashMap<>();
        authInfo.put(ResourceResolverFactory.SUBSERVICE, SERVICE_USER);
        return resolverFactory.getServiceResourceResolver(authInfo);
    }

    private String serializeParameters(Map<String, Object> parameters) {
        if (parameters == null) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(parameters);
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize parameters, using empty map", e);
            return "{}";
        }
    }

    private Map<String, Object> deserializeParameters(String json) {
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> result = OBJECT_MAPPER.readValue(json, MAP_TYPE);
            return result != null ? result : new HashMap<>();
        } catch (IOException e) {
            LOG.warn("Failed to deserialize parameters, using empty map", e);
            return new HashMap<>();
        }
    }

    private void ensureStoragePathExists() {
        // The storage path is created by repoinit, just verify it exists
        try (ResourceResolver resolver = getServiceResolver()) {
            Resource storagePath = resolver.getResource(STORAGE_PATH);
            if (storagePath == null) {
                LOG.warn("Storage path {} does not exist. Ensure repoinit has run.", STORAGE_PATH);
            } else {
                LOG.debug("Storage path {} exists and is accessible", STORAGE_PATH);
            }
        } catch (Exception e) {
            LOG.warn("Failed to verify storage path: {}. Jobs may fail to persist.", STORAGE_PATH, e);
        }
    }

    private void ensurePathExists(ResourceResolver resolver, String path) throws PersistenceException {
        if (resolver.getResource(path) != null) {
            return;
        }

        // Only create paths under the storage path (where we have permissions)
        // The storage path itself should be created by repoinit
        if (!path.startsWith(STORAGE_PATH)) {
            LOG.warn("Cannot create path outside storage path: {}", path);
            return;
        }

        // Get the subpath after the storage path
        String subPath = path.substring(STORAGE_PATH.length());
        if (subPath.isEmpty()) {
            return; // Nothing to create
        }

        // Create path recursively starting from STORAGE_PATH
        String[] segments = subPath.split("/");
        StringBuilder currentPath = new StringBuilder(STORAGE_PATH);
        
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            
            String parentPath = currentPath.toString();
            currentPath.append("/").append(segment);
            
            if (resolver.getResource(currentPath.toString()) == null) {
                Resource parent = resolver.getResource(parentPath);
                if (parent != null) {
                    Map<String, Object> props = new HashMap<>();
                    props.put(ResourceResolver.PROPERTY_RESOURCE_TYPE, NT_UNSTRUCTURED);
                    resolver.create(parent, segment, props);
                    LOG.debug("Created path: {}", currentPath);
                } else {
                    LOG.warn("Parent path not accessible: {}", parentPath);
                    return;
                }
            }
        }
    }
}
