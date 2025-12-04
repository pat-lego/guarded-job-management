package com.adobe.aem.support.core.guards.service;

import com.adobe.aem.support.core.guards.cluster.ClusterLeaderService;
import com.adobe.aem.support.core.guards.persistence.JobPersistenceService;
import com.adobe.aem.support.core.guards.persistence.JobPersistenceService.JobPersistenceException;
import com.adobe.aem.support.core.guards.persistence.JobPersistenceService.PersistedJob;
import com.adobe.aem.support.core.guards.service.impl.OrderedJobProcessor;
import com.adobe.aem.support.core.guards.token.GuardedOrderToken;
import com.adobe.aem.support.core.guards.token.GuardedOrderTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrderedJobProcessorTest {

    private static final String SECRET_KEY = "test-secret-key";
    private GuardedOrderTokenService tokenService;
    private JobPersistenceService persistenceService;
    private ClusterLeaderService clusterLeaderService;
    private OrderedJobProcessor processor;

    @BeforeEach
    void setUp() throws Exception {
        tokenService = new TestTokenService(SECRET_KEY);
        persistenceService = mock(JobPersistenceService.class);
        clusterLeaderService = mock(ClusterLeaderService.class);
        processor = new OrderedJobProcessor();
        
        // Set up leader service to return true (we are the leader)
        when(clusterLeaderService.isLeader()).thenReturn(true);
        when(clusterLeaderService.getSlingId()).thenReturn("test-sling-id");
        
        // Inject the token service via reflection
        Field tokenServiceField = OrderedJobProcessor.class.getDeclaredField("tokenService");
        tokenServiceField.setAccessible(true);
        tokenServiceField.set(processor, tokenService);
        
        // Inject the persistence service via reflection
        Field persistenceServiceField = OrderedJobProcessor.class.getDeclaredField("persistenceService");
        persistenceServiceField.setAccessible(true);
        persistenceServiceField.set(processor, persistenceService);
        
        // Inject the cluster leader service via reflection
        Field clusterLeaderServiceField = OrderedJobProcessor.class.getDeclaredField("clusterLeaderService");
        clusterLeaderServiceField.setAccessible(true);
        clusterLeaderServiceField.set(processor, clusterLeaderService);
        
        // Find the Config interface by name
        Class<?> configClass = findConfigClass();
        
        // Simulate OSGi activation with config
        java.lang.reflect.Method activateMethod = OrderedJobProcessor.class.getDeclaredMethod("activate", configClass);
        activateMethod.setAccessible(true);
        
        Object configProxy = java.lang.reflect.Proxy.newProxyInstance(
            OrderedJobProcessor.class.getClassLoader(),
            new Class<?>[] { configClass },
            (proxy, method, args) -> {
                if ("coalesceTimeMs".equals(method.getName())) {
                    return 10L;
                }
                if ("jobTimeoutSeconds".equals(method.getName())) {
                    return 30L;
                }
                if ("jobPollIntervalMs".equals(method.getName())) {
                    return 100L; // Fast polling for tests
                }
                return null;
            }
        );
        activateMethod.invoke(processor, configProxy);
    }

    private Class<?> findConfigClass() {
        for (Class<?> clazz : OrderedJobProcessor.class.getDeclaredClasses()) {
            if (clazz.getSimpleName().equals("Config")) {
                return clazz;
            }
        }
        throw new IllegalStateException("Config class not found in OrderedJobProcessor");
    }

    @AfterEach
    void tearDown() {
        if (processor != null && !processor.isShutdown()) {
            processor.shutdownNow();
        }
    }

    // === Validation Tests ===

    @Test
    void submit_throwsForNullTopic() {
        String token = tokenService.generateToken();
        
        assertThrows(IllegalArgumentException.class, 
            () -> processor.submit(null, token, testJob("test"), Collections.emptyMap()));
    }

    @Test
    void submit_throwsForEmptyTopic() {
        String token = tokenService.generateToken();
        
        assertThrows(IllegalArgumentException.class, 
            () -> processor.submit("", token, testJob("test"), Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, 
            () -> processor.submit("   ", token, testJob("test"), Collections.emptyMap()));
    }

    @Test
    void submit_throwsForNullToken() {
        assertThrows(IllegalArgumentException.class, 
            () -> processor.submit("topic", null, testJob("test"), Collections.emptyMap()));
    }

    @Test
    void submit_throwsForInvalidToken() {
        assertThrows(IllegalArgumentException.class, 
            () -> processor.submit("topic", "invalid-token", testJob("test"), Collections.emptyMap()));
    }

    @Test
    void submit_throwsForTamperedToken() {
        String token = tokenService.generateToken();
        String[] parts = token.split("\\.", 2);
        String tamperedToken = (Long.parseLong(parts[0]) + 1000) + "." + parts[1];
        
        assertThrows(IllegalArgumentException.class, 
            () -> processor.submit("topic", tamperedToken, testJob("test"), Collections.emptyMap()));
    }

    @Test
    void submit_throwsForNullJob() {
        String token = tokenService.generateToken();
        
        assertThrows(IllegalArgumentException.class, 
            () -> processor.submit("topic", token, null, Collections.emptyMap()));
    }

    // === Persistence Tests ===

    @Test
    void submit_persistsJobToJcr() throws Exception {
        String token = tokenService.generateToken();
        String expectedId = "/var/guarded-jobs/test-id";
        
        when(persistenceService.persist(eq("topic"), eq(token), eq("test-job"), any()))
            .thenReturn(expectedId);
        
        CompletableFuture<String> future = processor.submit("topic", token, 
            testJob("test-job"), Map.of("key", "value"));
        
        // Job is persisted, future completes with null (fire-and-forget)
        assertNull(future.get(1, TimeUnit.SECONDS));
        
        // Verify persistence was called
        verify(persistenceService).persist(eq("topic"), eq(token), eq("test-job"), any());
    }

    @Test
    void submit_failsWhenPersistenceFails() throws Exception {
        String token = tokenService.generateToken();
        
        when(persistenceService.persist(any(), any(), any(), any()))
            .thenThrow(new JobPersistenceException("Persistence failed"));
        
        CompletableFuture<String> future = processor.submit("topic", token, 
            testJob("test-job"), Collections.emptyMap());
        
        ExecutionException ex = assertThrows(ExecutionException.class, 
            () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof JobPersistenceException);
    }

    @Test
    void submit_capturesCorrectParameters() throws Exception {
        String token = tokenService.generateToken();
        Map<String, Object> params = Map.of("message", "hello", "count", 42);
        
        when(persistenceService.persist(any(), any(), any(), any()))
            .thenReturn("/var/guarded-jobs/id");
        
        processor.submit("my-topic", token, testJob("my-job"), params);
        
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(persistenceService).persist(eq("my-topic"), eq(token), eq("my-job"), paramsCaptor.capture());
        
        assertEquals("hello", paramsCaptor.getValue().get("message"));
        assertEquals(42, paramsCaptor.getValue().get("count"));
    }

    // === Shutdown Tests ===

    @Test
    void shutdown_rejectsNewJobs() {
        processor.shutdown();
        
        String token = tokenService.generateToken();
        CompletableFuture<String> future = processor.submit("topic", token, 
            testJob("test"), Collections.emptyMap());
        
        assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
    }

    @Test
    void isShutdown_returnsTrueAfterShutdown() {
        assertFalse(processor.isShutdown());
        processor.shutdown();
        assertTrue(processor.isShutdown());
    }

    @Test
    void isShutdown_returnsTrueAfterShutdownNow() {
        assertFalse(processor.isShutdown());
        processor.shutdownNow();
        assertTrue(processor.isShutdown());
    }

    // === Polling and Processing Tests ===

    @Test
    void pollAndProcess_executesJobsFromJcr() throws Exception {
        // Register a job implementation
        CountDownLatch jobExecuted = new CountDownLatch(1);
        GuardedJob<String> job = new GuardedJob<String>() {
            @Override
            public String getName() { return "test-job"; }
            @Override
            public String execute(Map<String, Object> parameters) {
                jobExecuted.countDown();
                return "done";
            }
        };
        
        // Register via reflection
        Field registeredJobsField = OrderedJobProcessor.class.getDeclaredField("registeredJobs");
        registeredJobsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, GuardedJob<?>> registeredJobs = (Map<String, GuardedJob<?>>) registeredJobsField.get(processor);
        registeredJobs.put("test-job", job);
        
        // Set up persisted job
        String token = tokenService.generateToken();
        PersistedJob persistedJob = new PersistedJob(
            "/var/guarded-jobs/job-1",
            "topic",
            token,
            "test-job",
            Map.of(),
            System.currentTimeMillis()
        );
        
        when(persistenceService.loadAll())
            .thenReturn(List.of(persistedJob))
            .thenReturn(List.of()); // Empty after first poll
        
        // Manually trigger poll to avoid timing issues
        java.lang.reflect.Method pollMethod = OrderedJobProcessor.class.getDeclaredMethod("pollAndProcessJobs");
        pollMethod.setAccessible(true);
        pollMethod.invoke(processor);
        
        // Wait for job execution (jobs run in separate threads)
        assertTrue(jobExecuted.await(5, TimeUnit.SECONDS));
        
        // Verify job was removed from persistence
        verify(persistenceService, timeout(5000)).remove("/var/guarded-jobs/job-1");
    }

    @Test
    void pollAndProcess_processesJobsInTokenOrder() throws Exception {
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch allJobsComplete = new CountDownLatch(3);
        
        // Create jobs that track execution order
        for (String name : List.of("job1", "job2", "job3")) {
            GuardedJob<String> job = new GuardedJob<String>() {
                @Override
                public String getName() { return name; }
                @Override
                public String execute(Map<String, Object> parameters) {
                    executionOrder.add(name);
                    allJobsComplete.countDown();
                    return "done";
                }
            };
            
            Field registeredJobsField = OrderedJobProcessor.class.getDeclaredField("registeredJobs");
            registeredJobsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, GuardedJob<?>> registeredJobs = (Map<String, GuardedJob<?>>) registeredJobsField.get(processor);
            registeredJobs.put(name, job);
        }
        
        // Generate tokens in order
        String token1 = tokenService.generateToken();
        String token2 = tokenService.generateToken();
        String token3 = tokenService.generateToken();
        
        // Create persisted jobs - returned in sorted order (as JCR query would return them)
        List<PersistedJob> persistedJobs = List.of(
            new PersistedJob("/var/guarded-jobs/1", "topic", token1, "job1", Map.of(), System.currentTimeMillis()),
            new PersistedJob("/var/guarded-jobs/2", "topic", token2, "job2", Map.of(), System.currentTimeMillis()),
            new PersistedJob("/var/guarded-jobs/3", "topic", token3, "job3", Map.of(), System.currentTimeMillis())
        );
        
        when(persistenceService.loadAll())
            .thenReturn(persistedJobs)
            .thenReturn(List.of()); // Empty after processing
        
        // Manually trigger poll
        java.lang.reflect.Method pollMethod = OrderedJobProcessor.class.getDeclaredMethod("pollAndProcessJobs");
        pollMethod.setAccessible(true);
        pollMethod.invoke(processor);
        
        // Wait for all jobs to complete
        assertTrue(allJobsComplete.await(5, TimeUnit.SECONDS));
        
        // Should be processed in token order
        assertEquals(List.of("job1", "job2", "job3"), executionOrder);
    }

    @Test
    void pollAndProcess_removesOrphanedJobs() throws Exception {
        String token = tokenService.generateToken();
        
        // No registered implementation for "unknown-job"
        PersistedJob orphanedJob = new PersistedJob(
            "/var/guarded-jobs/orphan",
            "topic",
            token,
            "unknown-job",
            Map.of(),
            System.currentTimeMillis()
        );
        
        when(persistenceService.loadAll())
            .thenReturn(List.of(orphanedJob))
            .thenReturn(List.of());
        
        // Manually trigger poll
        java.lang.reflect.Method pollMethod = OrderedJobProcessor.class.getDeclaredMethod("pollAndProcessJobs");
        pollMethod.setAccessible(true);
        pollMethod.invoke(processor);
        
        // Orphaned job should be removed immediately
        verify(persistenceService).remove("/var/guarded-jobs/orphan");
    }

    // === Pending Count Tests ===

    @Test
    void getPendingCount_returnsZeroWhenNoJobs() throws Exception {
        when(persistenceService.loadAll()).thenReturn(List.of());
        
        assertEquals(0, processor.getPendingCount("topic"));
    }

    @Test
    void getTotalPendingCount_returnsZeroWhenNoJobs() throws Exception {
        when(persistenceService.loadAll()).thenReturn(List.of());
        
        assertEquals(0, processor.getTotalPendingCount());
    }

    @Test
    void getPendingCount_countsJobsForTopic() throws Exception {
        String token1 = tokenService.generateToken();
        String token2 = tokenService.generateToken();
        String token3 = tokenService.generateToken();
        
        List<PersistedJob> jobs = List.of(
            new PersistedJob("/id1", "topic-a", token1, "job", Map.of(), 0),
            new PersistedJob("/id2", "topic-a", token2, "job", Map.of(), 0),
            new PersistedJob("/id3", "topic-b", token3, "job", Map.of(), 0)
        );
        
        when(persistenceService.loadAll()).thenReturn(jobs);
        
        assertEquals(2, processor.getPendingCount("topic-a"));
        assertEquals(1, processor.getPendingCount("topic-b"));
        assertEquals(0, processor.getPendingCount("topic-c"));
    }

    @Test
    void getTotalPendingCount_countsAllJobs() throws Exception {
        String token1 = tokenService.generateToken();
        String token2 = tokenService.generateToken();
        
        List<PersistedJob> jobs = List.of(
            new PersistedJob("/id1", "topic-a", token1, "job", Map.of(), 0),
            new PersistedJob("/id2", "topic-b", token2, "job", Map.of(), 0)
        );
        
        when(persistenceService.loadAll()).thenReturn(jobs);
        
        assertEquals(2, processor.getTotalPendingCount());
    }

    // === Async Job Tests ===

    @Test
    void asyncJob_timesOutAndIsRemovedFromJcr() throws Exception {
        // Create an async job that never completes
        CountDownLatch executeStarted = new CountDownLatch(1);
        GuardedJob<String> neverCompletingAsyncJob = new GuardedJob<String>() {
            @Override
            public String getName() { return "never-completing-async-job"; }
            
            @Override
            public String execute(Map<String, Object> parameters) {
                executeStarted.countDown();
                return "started";
            }
            
            @Override
            public boolean isAsync() {
                return true;
            }
            
            @Override
            public boolean isComplete(Map<String, Object> parameters) {
                // Never completes
                return false;
            }
            
            @Override
            public long getAsyncPollingIntervalMs() {
                return 50; // Fast polling for test
            }
            
            @Override
            public long getTimeoutSeconds() {
                return 1; // 1 second timeout for fast test
            }
        };
        
        // Register via reflection
        Field registeredJobsField = OrderedJobProcessor.class.getDeclaredField("registeredJobs");
        registeredJobsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, GuardedJob<?>> registeredJobs = (Map<String, GuardedJob<?>>) registeredJobsField.get(processor);
        registeredJobs.put("never-completing-async-job", neverCompletingAsyncJob);
        
        // Set up persisted job
        String token = tokenService.generateToken();
        PersistedJob persistedJob = new PersistedJob(
            "/var/guarded-jobs/async-timeout-job",
            "topic",
            token,
            "never-completing-async-job",
            Map.of(),
            System.currentTimeMillis()
        );
        
        when(persistenceService.loadAll())
            .thenReturn(List.of(persistedJob))
            .thenReturn(List.of()); // Empty after first poll
        
        // Manually trigger poll
        java.lang.reflect.Method pollMethod = OrderedJobProcessor.class.getDeclaredMethod("pollAndProcessJobs");
        pollMethod.setAccessible(true);
        pollMethod.invoke(processor);
        
        // Wait for execute to start
        assertTrue(executeStarted.await(2, TimeUnit.SECONDS), "Job execute() should have been called");
        
        // Wait for timeout + some buffer (job has 1s timeout)
        // Job should be removed from JCR after timeout
        verify(persistenceService, timeout(5000)).remove("/var/guarded-jobs/async-timeout-job");
    }

    @Test
    void asyncJob_completesSuccessfullyAndIsRemovedFromJcr() throws Exception {
        // Create an async job that completes after a few polls
        CountDownLatch executeStarted = new CountDownLatch(1);
        CountDownLatch jobCompleted = new CountDownLatch(1);
        AtomicInteger pollCount = new AtomicInteger(0);
        
        GuardedJob<String> completingAsyncJob = new GuardedJob<String>() {
            @Override
            public String getName() { return "completing-async-job"; }
            
            @Override
            public String execute(Map<String, Object> parameters) {
                executeStarted.countDown();
                return "started";
            }
            
            @Override
            public boolean isAsync() {
                return true;
            }
            
            @Override
            public boolean isComplete(Map<String, Object> parameters) {
                // Complete after 3 polls
                if (pollCount.incrementAndGet() >= 3) {
                    jobCompleted.countDown();
                    return true;
                }
                return false;
            }
            
            @Override
            public long getAsyncPollingIntervalMs() {
                return 50; // Fast polling for test
            }
            
            @Override
            public long getTimeoutSeconds() {
                return 5; // Enough time to complete
            }
        };
        
        // Register via reflection
        Field registeredJobsField = OrderedJobProcessor.class.getDeclaredField("registeredJobs");
        registeredJobsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, GuardedJob<?>> registeredJobs = (Map<String, GuardedJob<?>>) registeredJobsField.get(processor);
        registeredJobs.put("completing-async-job", completingAsyncJob);
        
        // Set up persisted job
        String token = tokenService.generateToken();
        PersistedJob persistedJob = new PersistedJob(
            "/var/guarded-jobs/async-success-job",
            "topic",
            token,
            "completing-async-job",
            Map.of(),
            System.currentTimeMillis()
        );
        
        when(persistenceService.loadAll())
            .thenReturn(List.of(persistedJob))
            .thenReturn(List.of()); // Empty after first poll
        
        // Manually trigger poll
        java.lang.reflect.Method pollMethod = OrderedJobProcessor.class.getDeclaredMethod("pollAndProcessJobs");
        pollMethod.setAccessible(true);
        pollMethod.invoke(processor);
        
        // Wait for execute to start
        assertTrue(executeStarted.await(2, TimeUnit.SECONDS), "Job execute() should have been called");
        
        // Wait for job to complete
        assertTrue(jobCompleted.await(2, TimeUnit.SECONDS), "Job should have completed");
        
        // Job should be removed from JCR after successful completion
        verify(persistenceService, timeout(5000)).remove("/var/guarded-jobs/async-success-job");
    }

    @Test
    void asyncJob_usesGlobalTimeoutWhenJobTimeoutNotSet() throws Exception {
        // Create an async job that doesn't specify timeout (returns -1)
        CountDownLatch executeStarted = new CountDownLatch(1);
        GuardedJob<String> asyncJobNoTimeout = new GuardedJob<String>() {
            @Override
            public String getName() { return "async-job-no-timeout"; }
            
            @Override
            public String execute(Map<String, Object> parameters) {
                executeStarted.countDown();
                return "started";
            }
            
            @Override
            public boolean isAsync() {
                return true;
            }
            
            @Override
            public boolean isComplete(Map<String, Object> parameters) {
                return false; // Never completes
            }
            
            @Override
            public long getAsyncPollingIntervalMs() {
                return 50;
            }
            
            @Override
            public long getTimeoutSeconds() {
                return -1; // Use global default
            }
        };
        
        // Register via reflection
        Field registeredJobsField = OrderedJobProcessor.class.getDeclaredField("registeredJobs");
        registeredJobsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, GuardedJob<?>> registeredJobs = (Map<String, GuardedJob<?>>) registeredJobsField.get(processor);
        registeredJobs.put("async-job-no-timeout", asyncJobNoTimeout);
        
        // Set up persisted job
        String token = tokenService.generateToken();
        PersistedJob persistedJob = new PersistedJob(
            "/var/guarded-jobs/async-global-timeout-job",
            "topic",
            token,
            "async-job-no-timeout",
            Map.of(),
            System.currentTimeMillis()
        );
        
        when(persistenceService.loadAll())
            .thenReturn(List.of(persistedJob))
            .thenReturn(List.of());
        
        // Reconfigure processor with a very short global timeout for this test
        Class<?> configClass = findConfigClass();
        java.lang.reflect.Method activateMethod = OrderedJobProcessor.class.getDeclaredMethod("activate", configClass);
        activateMethod.setAccessible(true);
        
        Object configProxy = java.lang.reflect.Proxy.newProxyInstance(
            OrderedJobProcessor.class.getClassLoader(),
            new Class<?>[] { configClass },
            (proxy, method, args) -> {
                if ("coalesceTimeMs".equals(method.getName())) {
                    return 10L;
                }
                if ("jobTimeoutSeconds".equals(method.getName())) {
                    return 1L; // Very short global timeout for test
                }
                if ("jobPollIntervalMs".equals(method.getName())) {
                    return 100L;
                }
                return null;
            }
        );
        activateMethod.invoke(processor, configProxy);
        
        // Manually trigger poll
        java.lang.reflect.Method pollMethod = OrderedJobProcessor.class.getDeclaredMethod("pollAndProcessJobs");
        pollMethod.setAccessible(true);
        pollMethod.invoke(processor);
        
        // Wait for execute to start
        assertTrue(executeStarted.await(2, TimeUnit.SECONDS), "Job execute() should have been called");
        
        // Should timeout using global timeout (1s) and be removed from JCR
        verify(persistenceService, timeout(5000)).remove("/var/guarded-jobs/async-global-timeout-job");
    }

    @Test
    void asyncJob_completesImmediatelyWhenIsCompleteNotOverridden() throws Exception {
        // Create an async job that does NOT override isComplete() (uses default which returns true)
        CountDownLatch executeCompleted = new CountDownLatch(1);
        GuardedJob<String> asyncJobWithoutIsComplete = new GuardedJob<String>() {
            @Override
            public String getName() { return "async-no-isComplete-override"; }
            
            @Override
            public String execute(Map<String, Object> parameters) {
                executeCompleted.countDown();
                return "started";
            }
            
            @Override
            public boolean isAsync() {
                return true;
            }
            
            // Note: isComplete() is NOT overridden - uses default which returns true
            
            @Override
            public long getTimeoutSeconds() {
                return 5;
            }
        };
        
        // Register via reflection
        Field registeredJobsField = OrderedJobProcessor.class.getDeclaredField("registeredJobs");
        registeredJobsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, GuardedJob<?>> registeredJobs = (Map<String, GuardedJob<?>>) registeredJobsField.get(processor);
        registeredJobs.put("async-no-isComplete-override", asyncJobWithoutIsComplete);
        
        // Set up persisted job
        String token = tokenService.generateToken();
        PersistedJob persistedJob = new PersistedJob(
            "/var/guarded-jobs/async-no-override-job",
            "topic",
            token,
            "async-no-isComplete-override",
            Map.of(),
            System.currentTimeMillis()
        );
        
        when(persistenceService.loadAll())
            .thenReturn(List.of(persistedJob))
            .thenReturn(List.of());
        
        // Manually trigger poll
        java.lang.reflect.Method pollMethod = OrderedJobProcessor.class.getDeclaredMethod("pollAndProcessJobs");
        pollMethod.setAccessible(true);
        pollMethod.invoke(processor);
        
        // Wait for execute to complete
        assertTrue(executeCompleted.await(2, TimeUnit.SECONDS), "Job execute() should have been called");
        
        // Job should still complete and be removed from JCR (even though isComplete wasn't overridden)
        // The warning log will be emitted but the job should still succeed
        verify(persistenceService, timeout(5000)).remove("/var/guarded-jobs/async-no-override-job");
    }

    // === Helper Methods ===

    private GuardedJob<String> testJob(String name) {
        return new GuardedJob<String>() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String execute(Map<String, Object> parameters) {
                return "result";
            }
        };
    }

    /**
     * Test implementation of GuardedOrderTokenService.
     */
    private static class TestTokenService implements GuardedOrderTokenService {
        private final GuardedOrderToken delegate;

        TestTokenService(String secretKey) {
            this.delegate = new GuardedOrderToken(secretKey);
        }

        @Override
        public String generateToken() {
            return delegate.generate();
        }

        @Override
        public boolean isValid(String token) {
            return delegate.isValid(token);
        }

        @Override
        public long extractTimestamp(String token) {
            return delegate.extractTimestamp(token);
        }

        @Override
        public int compare(String token1, String token2) {
            return delegate.compare(token1, token2);
        }
    }
}
