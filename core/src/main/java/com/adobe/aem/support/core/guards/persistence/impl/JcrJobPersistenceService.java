package com.adobe.aem.support.core.guards.persistence.impl;

import com.adobe.aem.support.core.guards.persistence.JobPersistenceService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.apache.sling.api.resource.*;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * JCR-based implementation of {@link JobPersistenceService}.
 * 
 * <p>Stores jobs as JSON blobs under a configurable path in the repository.
 * Each job is stored as a node with properties for metadata and a binary
 * property containing the serialized parameters.</p>
 * 
 * <p>Storage structure:
 * <pre>
 * /var/guarded-jobs/
 *   {topic}/
 *     {job-id}/
 *       - jcr:primaryType = nt:unstructured
 *       - token = "..."
 *       - jobName = "echo"
 *       - persistedAt = 1733325600000
 *       - parameters (binary JSON blob)
 * </pre>
 * </p>
 */
@Component(service = JobPersistenceService.class, immediate = true)
@Designate(ocd = JcrJobPersistenceService.Config.class)
public class JcrJobPersistenceService implements JobPersistenceService {

    private static final Logger LOG = LoggerFactory.getLogger(JcrJobPersistenceService.class);
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private static final String PROP_TOKEN = "token";
    private static final String PROP_JOB_NAME = "jobName";
    private static final String PROP_PERSISTED_AT = "persistedAt";
    private static final String PROP_PARAMETERS = "parameters";
    private static final String NT_UNSTRUCTURED = "nt:unstructured";

    private static final String STORAGE_PATH = "/var/guarded-jobs";
    private static final String SERVICE_USER = "guarded-job-service";

    @ObjectClassDefinition(
        name = "Guarded Job Persistence Service",
        description = "Configuration for persisting jobs to JCR for durability across restarts"
    )
    @interface Config {
        @AttributeDefinition(
            name = "Enabled",
            description = "Enable job persistence. When disabled, jobs are not persisted and will be lost on restart."
        )
        boolean enabled() default false;
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    private boolean enabled;

    @Activate
    protected void activate(Config config) {
        this.enabled = config.enabled();
        
        LOG.info("JcrJobPersistenceService activated: enabled={}, storagePath={}, serviceUser={}", 
            enabled, STORAGE_PATH, SERVICE_USER);
        
        if (enabled) {
            ensureStoragePathExists();
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String persist(String topic, String token, String jobName, Map<String, Object> parameters) 
            throws JobPersistenceException {
        
        if (!enabled) {
            return null;
        }

        String jobId = UUID.randomUUID().toString();
        String jobPath = STORAGE_PATH + "/" + sanitize(topic) + "/" + jobId;

        try (ResourceResolver resolver = getServiceResolver()) {
            // Ensure topic folder exists
            String topicPath = STORAGE_PATH + "/" + sanitize(topic);
            ensurePathExists(resolver, topicPath);

            // Create job node
            Resource topicResource = resolver.getResource(topicPath);
            if (topicResource == null) {
                throw new JobPersistenceException("Failed to access topic path: " + topicPath);
            }

            Map<String, Object> properties = new HashMap<>();
            properties.put(ResourceResolver.PROPERTY_RESOURCE_TYPE, NT_UNSTRUCTURED);
            properties.put(PROP_TOKEN, token);
            properties.put(PROP_JOB_NAME, jobName);
            properties.put(PROP_PERSISTED_AT, System.currentTimeMillis());
            
            // Store parameters as JSON blob
            String parametersJson = GSON.toJson(parameters);
            InputStream parametersStream = new ByteArrayInputStream(
                parametersJson.getBytes(StandardCharsets.UTF_8));
            properties.put(PROP_PARAMETERS, parametersStream);

            resolver.create(topicResource, jobId, properties);
            resolver.commit();

            LOG.debug("Persisted job: topic={}, jobName={}, id={}", topic, jobName, jobId);
            return jobPath;

        } catch (LoginException e) {
            throw new JobPersistenceException("Failed to get service resolver", e);
        } catch (PersistenceException e) {
            throw new JobPersistenceException("Failed to persist job: " + jobPath, e);
        }
    }

    @Override
    public void remove(String persistenceId) throws JobPersistenceException {
        if (!enabled || persistenceId == null) {
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

        if (!enabled) {
            return jobs;
        }

        try (ResourceResolver resolver = getServiceResolver()) {
            Resource storageResource = resolver.getResource(STORAGE_PATH);
            if (storageResource == null) {
                LOG.debug("Storage path does not exist: {}", STORAGE_PATH);
                return jobs;
            }

            // Iterate through topic folders
            for (Resource topicResource : storageResource.getChildren()) {
                String topic = topicResource.getName();
                
                // Iterate through job nodes in each topic
                for (Resource jobResource : topicResource.getChildren()) {
                    try {
                        PersistedJob job = loadJob(topic, jobResource);
                        if (job != null) {
                            jobs.add(job);
                        }
                    } catch (Exception e) {
                        LOG.warn("Failed to load persisted job: {}", jobResource.getPath(), e);
                    }
                }
            }

            LOG.info("Loaded {} persisted jobs from {}", jobs.size(), STORAGE_PATH);
            return jobs;

        } catch (LoginException e) {
            throw new JobPersistenceException("Failed to get service resolver", e);
        }
    }

    private PersistedJob loadJob(String topic, Resource jobResource) {
        ValueMap properties = jobResource.getValueMap();
        
        String token = properties.get(PROP_TOKEN, String.class);
        String jobName = properties.get(PROP_JOB_NAME, String.class);
        Long persistedAt = properties.get(PROP_PERSISTED_AT, Long.class);
        
        if (token == null || jobName == null) {
            LOG.warn("Invalid persisted job (missing required properties): {}", jobResource.getPath());
            return null;
        }

        // Load parameters from binary property
        Map<String, Object> parameters = new HashMap<>();
        InputStream parametersStream = properties.get(PROP_PARAMETERS, InputStream.class);
        if (parametersStream != null) {
            try {
                String json = new String(parametersStream.readAllBytes(), StandardCharsets.UTF_8);
                parameters = GSON.fromJson(json, MAP_TYPE);
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

    private String sanitize(String name) {
        // Remove characters that are invalid in JCR node names
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}

