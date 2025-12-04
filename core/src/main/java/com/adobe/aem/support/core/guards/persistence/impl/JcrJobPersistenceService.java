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
 * Jobs are stored as JSON blobs organized by Sling ID and date to prevent large node trees.
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
 *             - token = "..."
 *             - jobName = "echo"
 *             - persistedAt = 1733325600000
 *             - parameters (binary JSON blob)
 * </pre>
 * </p>
 */
@Component(service = JobPersistenceService.class, immediate = true)
public class JcrJobPersistenceService implements JobPersistenceService {

    private static final Logger LOG = LoggerFactory.getLogger(JcrJobPersistenceService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String PROP_TOPIC = "topic";
    private static final String PROP_TOKEN = "token";
    private static final String PROP_JOB_NAME = "jobName";
    private static final String PROP_PERSISTED_AT = "persistedAt";
    private static final String PROP_PARAMETERS = "parameters";
    private static final String NT_UNSTRUCTURED = "nt:unstructured";

    private static final String STORAGE_PATH = "/var/guarded-jobs";
    private static final String SERVICE_USER = "guarded-job-service";

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private ClusterLeaderService clusterLeaderService;

    @Activate
    protected void activate() {
        LOG.info("JcrJobPersistenceService activated: slingId={}, storagePath={}", 
            clusterLeaderService.getSlingId(), STORAGE_PATH);
        
        ensureStoragePathExists();
    }

    @Override
    public boolean isEnabled() {
        return true; // Always enabled
    }

    @Override
    public String persist(String topic, String token, String jobName, Map<String, Object> parameters) 
            throws JobPersistenceException {

        String slingId = clusterLeaderService.getSlingId();
        String jobId = UUID.randomUUID().toString();
        String datePath = buildDatePath();
        String jobPath = STORAGE_PATH + "/" + slingId + "/" + datePath + "/" + jobId;

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
            properties.put(PROP_TOPIC, topic);
            properties.put(PROP_TOKEN, token);
            properties.put(PROP_JOB_NAME, jobName);
            properties.put(PROP_PERSISTED_AT, System.currentTimeMillis());
            
            // Store parameters as JSON blob
            String parametersJson = serializeParameters(parameters);
            InputStream parametersStream = new ByteArrayInputStream(
                parametersJson.getBytes(StandardCharsets.UTF_8));
            properties.put(PROP_PARAMETERS, parametersStream);

            resolver.create(parentResource, jobId, properties);
            resolver.commit();

            LOG.debug("Persisted job: topic={}, jobName={}, path={}", topic, jobName, jobPath);
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

            LOG.info("Loaded {} persisted jobs from {}", jobs.size(), STORAGE_PATH);
            return jobs;

        } catch (LoginException e) {
            throw new JobPersistenceException("Failed to get service resolver", e);
        }
    }

    private PersistedJob loadJob(Resource jobResource) {
        ValueMap properties = jobResource.getValueMap();
        
        String topic = properties.get(PROP_TOPIC, String.class);
        String token = properties.get(PROP_TOKEN, String.class);
        String jobName = properties.get(PROP_JOB_NAME, String.class);
        Long persistedAt = properties.get(PROP_PERSISTED_AT, Long.class);
        
        if (topic == null || token == null || jobName == null) {
            LOG.warn("Invalid persisted job (missing required properties): {}", jobResource.getPath());
            return null;
        }

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
        try (ResourceResolver resolver = getServiceResolver()) {
            ensurePathExists(resolver, STORAGE_PATH);
            resolver.commit();
        } catch (Exception e) {
            LOG.warn("Failed to create storage path: {}. Jobs may fail to persist.", STORAGE_PATH, e);
        }
    }

    private void ensurePathExists(ResourceResolver resolver, String path) throws PersistenceException {
        if (resolver.getResource(path) != null) {
            return;
        }

        // Create path recursively
        String[] segments = path.split("/");
        StringBuilder currentPath = new StringBuilder();
        
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            
            String parentPath = currentPath.toString();
            currentPath.append("/").append(segment);
            
            if (resolver.getResource(currentPath.toString()) == null) {
                Resource parent = resolver.getResource(parentPath.isEmpty() ? "/" : parentPath);
                if (parent != null) {
                    Map<String, Object> props = new HashMap<>();
                    props.put(ResourceResolver.PROPERTY_RESOURCE_TYPE, NT_UNSTRUCTURED);
                    resolver.create(parent, segment, props);
                }
            }
        }
    }
}
