package com.adobe.aem.support.core.guards.persistence;

import com.adobe.aem.support.core.guards.cluster.ClusterLeaderService;
import com.adobe.aem.support.core.guards.persistence.JobPersistenceService.PersistedJob;
import com.adobe.aem.support.core.guards.persistence.impl.JcrJobPersistenceService;
import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;
import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith({AemContextExtension.class, MockitoExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class JcrJobPersistenceServiceTest {

    // Use default RESOURCERESOLVER_MOCK (no Oak dependency needed)
    private final AemContext context = new AemContext();

    @Mock
    private ClusterLeaderService clusterLeaderService;

    private JcrJobPersistenceService persistenceService;

    private static final String TEST_SLING_ID = "test-sling-id-12345";
    private static final String STORAGE_PATH = "/var/guarded-jobs";

    @BeforeEach
    void setUp() throws Exception {
        // Set up the mock ClusterLeaderService
        when(clusterLeaderService.getSlingId()).thenReturn(TEST_SLING_ID);
        when(clusterLeaderService.isLeader()).thenReturn(true);

        // Register services in the context
        context.registerService(ClusterLeaderService.class, clusterLeaderService);

        // Create the storage path structure (the service also does this, but mock needs it)
        context.create().resource(STORAGE_PATH);
        context.resourceResolver().commit();

        // Create and activate the service
        persistenceService = context.registerInjectActivateService(new JcrJobPersistenceService());
    }

    @Test
    void isEnabled_alwaysReturnsTrue() {
        assertTrue(persistenceService.isEnabled());
    }

    @Test
    void persist_createsJobInJcr() throws Exception {
        String topic = "test-topic";
        String token = "1234567890.abc123signature";
        String jobName = "echo";
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("message", "Hello World");
        parameters.put("count", 42);

        String persistenceId = persistenceService.persist(topic, token, jobName, parameters);

        assertNotNull(persistenceId);
        assertTrue(persistenceId.startsWith(STORAGE_PATH + "/" + TEST_SLING_ID));

        // Verify the resource was created
        Resource jobResource = context.resourceResolver().getResource(persistenceId);
        assertNotNull(jobResource, "Job resource should exist at: " + persistenceId);

        // Verify properties
        assertEquals(topic, jobResource.getValueMap().get("topic", String.class));
        assertEquals(token, jobResource.getValueMap().get("token", String.class));
        assertEquals(jobName, jobResource.getValueMap().get("jobName", String.class));
        assertNotNull(jobResource.getValueMap().get("persistedAt", Long.class));
    }

    @Test
    void persist_createsDateBasedPath() throws Exception {
        String persistenceId = persistenceService.persist("topic", "token.sig", "job", Map.of());

        // Path should include date components: /var/guarded-jobs/{slingId}/{year}/{month}/{day}/{jobId}
        String[] segments = persistenceId.split("/");
        assertTrue(segments.length >= 7, "Path should have date segments: " + persistenceId);
        
        // Verify the year segment is a valid year (e.g., 2024, 2025)
        String yearSegment = segments[4]; // After /var/guarded-jobs/{slingId}/
        int year = Integer.parseInt(yearSegment);
        assertTrue(year >= 2024 && year <= 2100, "Year should be valid: " + year);
    }

    @Test
    void persist_storesParametersAsJson() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("stringParam", "test value");
        parameters.put("intParam", 123);
        parameters.put("boolParam", true);

        persistenceService.persist("topic", "token.sig", "job", parameters);

        // Load the job and verify parameters
        when(clusterLeaderService.isLeader()).thenReturn(true);
        List<PersistedJob> jobs = persistenceService.loadAll();
        
        assertEquals(1, jobs.size());
        Map<String, Object> loadedParams = jobs.get(0).getParameters();
        assertEquals("test value", loadedParams.get("stringParam"));
        // Jackson preserves integer types
        assertEquals(123, loadedParams.get("intParam"));
        assertEquals(true, loadedParams.get("boolParam"));
    }

    @Test
    void remove_deletesJobFromJcr() throws Exception {
        String persistenceId = persistenceService.persist("topic", "token.sig", "job", Map.of());

        // Verify it exists
        assertNotNull(context.resourceResolver().getResource(persistenceId));

        // Remove it
        persistenceService.remove(persistenceId);
        context.resourceResolver().refresh();

        // Verify it's gone
        assertNull(context.resourceResolver().getResource(persistenceId));
    }

    @Test
    void remove_handlesNullPersistenceId() throws Exception {
        // Should not throw
        assertDoesNotThrow(() -> persistenceService.remove(null));
    }

    @Test
    void remove_handlesNonExistentPath() throws Exception {
        // Should not throw
        assertDoesNotThrow(() -> persistenceService.remove("/var/guarded-jobs/nonexistent/path"));
    }

    @Test
    void loadAll_returnsEmptyListWhenNotLeader() throws Exception {
        // Persist a job
        persistenceService.persist("topic", "token.sig", "job", Map.of());

        // Set as non-leader
        when(clusterLeaderService.isLeader()).thenReturn(false);

        List<PersistedJob> jobs = persistenceService.loadAll();

        assertTrue(jobs.isEmpty());
    }

    @Test
    void loadAll_loadsAllJobsWhenLeader() throws Exception {
        // Persist multiple jobs
        persistenceService.persist("topic1", "token1.sig", "job1", Map.of("key", "value1"));
        persistenceService.persist("topic2", "token2.sig", "job2", Map.of("key", "value2"));
        persistenceService.persist("topic3", "token3.sig", "job3", Map.of("key", "value3"));

        when(clusterLeaderService.isLeader()).thenReturn(true);

        List<PersistedJob> jobs = persistenceService.loadAll();

        assertEquals(3, jobs.size());
    }

    @Test
    void loadAll_returnsCorrectJobData() throws Exception {
        String topic = "my-topic";
        String token = "1733325600.signature123";
        String jobName = "echo-job";
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("message", "Hello");

        persistenceService.persist(topic, token, jobName, parameters);

        when(clusterLeaderService.isLeader()).thenReturn(true);
        List<PersistedJob> jobs = persistenceService.loadAll();

        assertEquals(1, jobs.size());
        PersistedJob job = jobs.get(0);
        
        assertEquals(topic, job.getTopic());
        assertEquals(token, job.getToken());
        assertEquals(jobName, job.getJobName());
        assertEquals("Hello", job.getParameters().get("message"));
        assertTrue(job.getPersistedAt() > 0);
        assertNotNull(job.getPersistenceId());
    }

    @Test
    void loadAll_returnsEmptyListWhenNoJobs() throws Exception {
        when(clusterLeaderService.isLeader()).thenReturn(true);

        List<PersistedJob> jobs = persistenceService.loadAll();

        assertTrue(jobs.isEmpty());
    }

    @Test
    void loadAll_loadsJobsFromAllSlingIds() throws Exception {
        // Persist job with current sling ID
        persistenceService.persist("topic1", "token1.sig", "job1", Map.of());

        // Manually create a job under a different sling ID to simulate another instance
        String otherSlingIdPath = STORAGE_PATH + "/other-sling-id/2024/12/04/job-uuid";
        context.create().resource(otherSlingIdPath,
            "topic", "topic2",
            "token", "token2.sig",
            "jobName", "job2",
            "persistedAt", System.currentTimeMillis());
        context.resourceResolver().commit();

        when(clusterLeaderService.isLeader()).thenReturn(true);
        List<PersistedJob> jobs = persistenceService.loadAll();

        assertEquals(2, jobs.size());
    }

    @Test
    void persist_multipleJobsSameDay() throws Exception {
        // Persist multiple jobs on the same day
        String id1 = persistenceService.persist("topic", "token1.sig", "job1", Map.of());
        String id2 = persistenceService.persist("topic", "token2.sig", "job2", Map.of());
        String id3 = persistenceService.persist("topic", "token3.sig", "job3", Map.of());

        // All should be persisted
        assertNotNull(context.resourceResolver().getResource(id1));
        assertNotNull(context.resourceResolver().getResource(id2));
        assertNotNull(context.resourceResolver().getResource(id3));

        // All should have unique paths
        assertNotEquals(id1, id2);
        assertNotEquals(id2, id3);

        // Load all
        when(clusterLeaderService.isLeader()).thenReturn(true);
        List<PersistedJob> jobs = persistenceService.loadAll();
        assertEquals(3, jobs.size());
    }

    @Test
    void fullLifecycle_persistLoadRemove() throws Exception {
        // Persist
        String topic = "lifecycle-topic";
        String token = "lifecycle.token";
        String jobName = "lifecycle-job";
        Map<String, Object> params = Map.of("step", "test");

        String persistenceId = persistenceService.persist(topic, token, jobName, params);
        assertNotNull(persistenceId);

        // Load
        when(clusterLeaderService.isLeader()).thenReturn(true);
        List<PersistedJob> jobs = persistenceService.loadAll();
        assertEquals(1, jobs.size());
        assertEquals(topic, jobs.get(0).getTopic());

        // Remove
        persistenceService.remove(persistenceId);

        // Verify removed
        context.resourceResolver().refresh();
        jobs = persistenceService.loadAll();
        assertTrue(jobs.isEmpty());
    }

    @Test
    void persist_handlesEmptyParameters() throws Exception {
        String persistenceId = persistenceService.persist("topic", "token.sig", "job", Map.of());

        when(clusterLeaderService.isLeader()).thenReturn(true);
        List<PersistedJob> jobs = persistenceService.loadAll();

        assertEquals(1, jobs.size());
        assertTrue(jobs.get(0).getParameters().isEmpty());
    }

    @Test
    void persist_handlesNullParameters() throws Exception {
        persistenceService.persist("topic", "token.sig", "job", null);

        when(clusterLeaderService.isLeader()).thenReturn(true);
        List<PersistedJob> jobs = persistenceService.loadAll();

        assertEquals(1, jobs.size());
        // Null should be serialized as "null" JSON and deserialized back to null or empty
        assertNotNull(jobs.get(0).getParameters());
    }
}
