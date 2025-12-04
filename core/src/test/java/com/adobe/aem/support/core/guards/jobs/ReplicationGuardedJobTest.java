package com.adobe.aem.support.core.guards.jobs;

import com.adobe.aem.support.core.guards.servlets.JobSubmitServlet;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationOptions;
import com.day.cq.replication.Replicator;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.jcr.Session;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplicationGuardedJobTest {

    @Mock
    private Replicator replicator;

    @Mock
    private ResourceResolver resourceResolver;

    @Mock
    private Session session;

    private ReplicationGuardedJob job;

    @BeforeEach
    void setUp() throws Exception {
        job = new ReplicationGuardedJob();
        
        // Inject the replicator via reflection
        Field replicatorField = ReplicationGuardedJob.class.getDeclaredField("replicator");
        replicatorField.setAccessible(true);
        replicatorField.set(job, replicator);
        
        // Set up ResourceResolver to return Session
        when(resourceResolver.adaptTo(Session.class)).thenReturn(session);
    }

    // === Basic Tests ===

    @Test
    void getName_returnsReplicate() {
        assertEquals("replicate", job.getName());
    }

    @Test
    void isAsync_returnsFalse() {
        // Job should be synchronous (default)
        assertFalse(job.isAsync());
    }

    // === ResourceResolver Tests ===

    @Test
    void execute_failsWithoutResourceResolver() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("paths", new String[]{"/content/test"});
        // No ResourceResolver in parameters

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> job.execute(parameters));
        
        assertTrue(exception.getMessage().contains("ResourceResolver not found"));
    }

    @Test
    void execute_failsWhenSessionCannotBeObtained() {
        when(resourceResolver.adaptTo(Session.class)).thenReturn(null);
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", new String[]{"/content/test"});

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> job.execute(parameters));
        
        assertTrue(exception.getMessage().contains("Could not obtain JCR session"));
    }

    // === Paths Parsing Tests ===

    @Test
    void execute_failsWithoutPaths() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        // No paths

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> job.execute(parameters));
        
        assertTrue(exception.getMessage().contains("Missing required parameter: paths"));
    }

    @Test
    void execute_failsWithEmptyPaths() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", new String[]{});

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> job.execute(parameters));
        
        assertTrue(exception.getMessage().contains("Missing required parameter: paths"));
    }

    @Test
    void execute_parsesStringArrayPaths() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", new String[]{"/content/a", "/content/b"});

        job.execute(parameters);

        // Verify both paths were replicated in a single batch call
        ArgumentCaptor<String[]> pathsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(replicator).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), 
            pathsCaptor.capture(), any(ReplicationOptions.class));
        
        assertArrayEquals(new String[]{"/content/a", "/content/b"}, pathsCaptor.getValue());
    }

    @Test
    void execute_parsesListPaths() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", List.of("/content/a", "/content/b", "/content/c"));

        job.execute(parameters);

        ArgumentCaptor<String[]> pathsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(replicator).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), 
            pathsCaptor.capture(), any(ReplicationOptions.class));
        
        assertArrayEquals(new String[]{"/content/a", "/content/b", "/content/c"}, pathsCaptor.getValue());
    }

    @Test
    void execute_parsesCommaSeparatedPaths() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", "/content/a, /content/b , /content/c");

        job.execute(parameters);

        ArgumentCaptor<String[]> pathsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(replicator).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), 
            pathsCaptor.capture(), any(ReplicationOptions.class));
        
        assertArrayEquals(new String[]{"/content/a", "/content/b", "/content/c"}, pathsCaptor.getValue());
    }

    @Test
    void execute_ignoresEmptyPathsInCommaSeparated() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", "/content/a,,/content/b, ,/content/c");

        job.execute(parameters);

        ArgumentCaptor<String[]> pathsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(replicator).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), 
            pathsCaptor.capture(), any(ReplicationOptions.class));
        
        assertArrayEquals(new String[]{"/content/a", "/content/b", "/content/c"}, pathsCaptor.getValue());
    }

    // === Batching Tests ===

    @Test
    void execute_singleBatchForLessThan10Paths() throws Exception {
        String[] paths = new String[]{"/content/1", "/content/2", "/content/3"};
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", paths);

        job.execute(parameters);

        // Should be exactly 1 call to replicate
        verify(replicator, times(1)).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), 
            any(String[].class), any(ReplicationOptions.class));
    }

    @Test
    void execute_singleBatchForExactly10Paths() throws Exception {
        String[] paths = new String[10];
        for (int i = 0; i < 10; i++) {
            paths[i] = "/content/page" + i;
        }
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", paths);

        job.execute(parameters);

        // Should be exactly 1 call to replicate
        verify(replicator, times(1)).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), 
            any(String[].class), any(ReplicationOptions.class));
        
        // Verify the batch contains all 10 paths
        ArgumentCaptor<String[]> pathsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(replicator).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), 
            pathsCaptor.capture(), any(ReplicationOptions.class));
        assertEquals(10, pathsCaptor.getValue().length);
    }

    @Test
    void execute_twoBatchesFor11Paths() throws Exception {
        String[] paths = new String[11];
        for (int i = 0; i < 11; i++) {
            paths[i] = "/content/page" + i;
        }
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", paths);

        job.execute(parameters);

        // Should be 2 calls to replicate
        verify(replicator, times(2)).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), 
            any(String[].class), any(ReplicationOptions.class));
    }

    @Test
    void execute_correctBatchSizesFor25Paths() throws Exception {
        String[] paths = new String[25];
        for (int i = 0; i < 25; i++) {
            paths[i] = "/content/page" + i;
        }
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", paths);

        job.execute(parameters);

        // Should be 3 calls: 10 + 10 + 5
        ArgumentCaptor<String[]> pathsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(replicator, times(3)).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), 
            pathsCaptor.capture(), any(ReplicationOptions.class));
        
        List<String[]> allBatches = pathsCaptor.getAllValues();
        assertEquals(3, allBatches.size());
        assertEquals(10, allBatches.get(0).length);
        assertEquals(10, allBatches.get(1).length);
        assertEquals(5, allBatches.get(2).length);
        
        // Verify order is preserved
        assertEquals("/content/page0", allBatches.get(0)[0]);
        assertEquals("/content/page9", allBatches.get(0)[9]);
        assertEquals("/content/page10", allBatches.get(1)[0]);
        assertEquals("/content/page19", allBatches.get(1)[9]);
        assertEquals("/content/page20", allBatches.get(2)[0]);
        assertEquals("/content/page24", allBatches.get(2)[4]);
    }

    @Test
    void execute_batchingPreservesPathOrder() throws Exception {
        // Create 15 paths with distinct names to verify order
        String[] paths = new String[15];
        for (int i = 0; i < 15; i++) {
            paths[i] = "/content/ordered-" + String.format("%02d", i);
        }
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", paths);

        job.execute(parameters);

        ArgumentCaptor<String[]> pathsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(replicator, times(2)).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), 
            pathsCaptor.capture(), any(ReplicationOptions.class));
        
        List<String[]> batches = pathsCaptor.getAllValues();
        
        // First batch: 0-9
        for (int i = 0; i < 10; i++) {
            assertEquals("/content/ordered-" + String.format("%02d", i), batches.get(0)[i]);
        }
        
        // Second batch: 10-14
        for (int i = 0; i < 5; i++) {
            assertEquals("/content/ordered-" + String.format("%02d", i + 10), batches.get(1)[i]);
        }
    }

    // === Action Type Tests ===

    @Test
    void execute_defaultsToActivate() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", new String[]{"/content/test"});
        // No action specified

        job.execute(parameters);

        verify(replicator).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), 
            any(String[].class), any(ReplicationOptions.class));
    }

    @Test
    void execute_parsesActivateAction() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", new String[]{"/content/test"});
        parameters.put("action", "ACTIVATE");

        job.execute(parameters);

        verify(replicator).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), 
            any(String[].class), any(ReplicationOptions.class));
    }

    @Test
    void execute_parsesDeactivateAction() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", new String[]{"/content/test"});
        parameters.put("action", "DEACTIVATE");

        job.execute(parameters);

        verify(replicator).replicate(eq(session), eq(ReplicationActionType.DEACTIVATE), 
            any(String[].class), any(ReplicationOptions.class));
    }

    @Test
    void execute_parsesDeleteAction() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", new String[]{"/content/test"});
        parameters.put("action", "DELETE");

        job.execute(parameters);

        verify(replicator).replicate(eq(session), eq(ReplicationActionType.DELETE), 
            any(String[].class), any(ReplicationOptions.class));
    }

    @Test
    void execute_parsesActionCaseInsensitive() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", new String[]{"/content/test"});
        parameters.put("action", "deactivate");

        job.execute(parameters);

        verify(replicator).replicate(eq(session), eq(ReplicationActionType.DEACTIVATE), 
            any(String[].class), any(ReplicationOptions.class));
    }

    @Test
    void execute_defaultsToActivateForUnknownAction() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", new String[]{"/content/test"});
        parameters.put("action", "UNKNOWN_ACTION");

        job.execute(parameters);

        verify(replicator).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), 
            any(String[].class), any(ReplicationOptions.class));
    }

    // === Synchronous Replication Tests ===

    @Test
    void execute_alwaysUsesSynchronousReplication() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", new String[]{"/content/test"});

        job.execute(parameters);

        ArgumentCaptor<ReplicationOptions> optionsCaptor = ArgumentCaptor.forClass(ReplicationOptions.class);
        verify(replicator).replicate(eq(session), any(ReplicationActionType.class), 
            any(String[].class), optionsCaptor.capture());
        
        assertTrue(optionsCaptor.getValue().isSynchronous());
    }

    // === Result Tests ===

    @Test
    void execute_returnsCompletedResult() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", new String[]{"/content/a", "/content/b"});

        ReplicationGuardedJob.ReplicationResult result = job.execute(parameters);

        assertNotNull(result);
        assertEquals("COMPLETED", result.getState());
        assertArrayEquals(new String[]{"/content/a", "/content/b"}, result.getPaths());
        assertTrue(result.getMessage().contains("successfully"));
        assertNotNull(result.getRequestId());
        assertTrue(result.getRequestId().startsWith("repl-"));
    }

    @Test
    void execute_resultToStringIsReadable() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", new String[]{"/content/test"});

        ReplicationGuardedJob.ReplicationResult result = job.execute(parameters);
        String str = result.toString();

        assertTrue(str.contains("requestId="));
        assertTrue(str.contains("state='COMPLETED'"));
        assertTrue(str.contains("/content/test"));
    }

    // === Error Handling Tests ===

    @Test
    void execute_throwsRuntimeExceptionOnReplicationFailure() throws Exception {
        com.day.cq.replication.ReplicationException replicationException = 
            new com.day.cq.replication.ReplicationException("Connection refused");
        
        doThrow(replicationException)
            .when(replicator).replicate(any(Session.class), any(ReplicationActionType.class), 
                any(String[].class), any(ReplicationOptions.class));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", new String[]{"/content/test"});

        RuntimeException ex = assertThrows(RuntimeException.class, () -> job.execute(parameters));
        assertTrue(ex.getMessage().contains("Replication failed"));
        assertTrue(ex.getCause() instanceof com.day.cq.replication.ReplicationException);
    }

    // === ArrayList Path Type Test ===

    @Test
    void execute_parsesArrayListPaths() throws Exception {
        ArrayList<String> paths = new ArrayList<>();
        paths.add("/content/a");
        paths.add("/content/b");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", paths);

        job.execute(parameters);

        ArgumentCaptor<String[]> pathsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(replicator).replicate(eq(session), eq(ReplicationActionType.ACTIVATE), 
            pathsCaptor.capture(), any(ReplicationOptions.class));
        
        assertArrayEquals(new String[]{"/content/a", "/content/b"}, pathsCaptor.getValue());
    }

    @Test
    void execute_throwsForInvalidPathType() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(JobSubmitServlet.PARAM_RESOURCE_RESOLVER, resourceResolver);
        parameters.put("paths", 12345); // Invalid type

        assertThrows(IllegalArgumentException.class, () -> job.execute(parameters));
    }

    // === GuardedJob Interface Default Methods ===

    @Test
    void isAsync_returnsFalseByDefault() {
        assertFalse(job.isAsync());
    }

    @Test
    void isComplete_returnsTrueByDefault() throws Exception {
        assertTrue(job.isComplete(new HashMap<>()));
    }

    @Test
    void getAsyncPollingIntervalMs_returnsDefault() {
        assertEquals(1000, job.getAsyncPollingIntervalMs());
    }

    @Test
    void getTimeoutSeconds_returnsNegativeOneByDefault() {
        assertEquals(-1, job.getTimeoutSeconds());
    }
}

