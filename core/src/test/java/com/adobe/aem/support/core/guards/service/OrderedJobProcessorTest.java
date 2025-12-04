package com.adobe.aem.support.core.guards.service;

import com.adobe.aem.support.core.guards.service.impl.OrderedJobProcessor;
import com.adobe.aem.support.core.guards.token.GuardedOrderToken;
import com.adobe.aem.support.core.guards.token.GuardedOrderTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class OrderedJobProcessorTest {

    private static final String SECRET_KEY = "test-secret-key";
    private GuardedOrderTokenService tokenService;
    private OrderedJobProcessor processor;

    @BeforeEach
    void setUp() throws Exception {
        tokenService = new TestTokenService(SECRET_KEY);
        processor = new OrderedJobProcessor();
        
        // Inject the token service via reflection (simulating OSGi injection)
        Field tokenServiceField = OrderedJobProcessor.class.getDeclaredField("tokenService");
        tokenServiceField.setAccessible(true);
        tokenServiceField.set(processor, tokenService);
        
        // Find the Config interface by name
        Class<?> configClass = findConfigClass();
        
        // Simulate OSGi activation with config
        java.lang.reflect.Method activateMethod = OrderedJobProcessor.class.getDeclaredMethod("activate", configClass);
        activateMethod.setAccessible(true);
        
        // Create a proxy for the Config interface
        Object configProxy = java.lang.reflect.Proxy.newProxyInstance(
            OrderedJobProcessor.class.getClassLoader(),
            new Class<?>[] { configClass },
            (proxy, method, args) -> {
                if ("coalesceTimeMs".equals(method.getName())) {
                    return 10L; // 10ms coalesce time for tests
                }
                if ("jobTimeoutSeconds".equals(method.getName())) {
                    return 30L; // 30s timeout for tests
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

    @Test
    void submit_executesJob() throws Exception {
        String token = tokenService.generateToken();
        
        CompletableFuture<String> future = processor.submit("topic", token, 
            testJob("test", params -> "result"), Collections.emptyMap());
        
        assertEquals("result", future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void submit_processesInTokenOrder() throws Exception {
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        
        // Generate tokens in order
        String token1 = tokenService.generateToken();
        String token2 = tokenService.generateToken();
        String token3 = tokenService.generateToken();
        
        // Submit in reverse order
        CompletableFuture<Void> future3 = processor.submit("topic", token3, 
            testJob("job3", params -> {
                executionOrder.add("third");
                return null;
            }), Collections.emptyMap());
        
        CompletableFuture<Void> future2 = processor.submit("topic", token2, 
            testJob("job2", params -> {
                executionOrder.add("second");
                return null;
            }), Collections.emptyMap());
        
        CompletableFuture<Void> future1 = processor.submit("topic", token1, 
            testJob("job1", params -> {
                executionOrder.add("first");
                return null;
            }), Collections.emptyMap());
        
        CompletableFuture.allOf(future1, future2, future3).get(5, TimeUnit.SECONDS);
        
        assertEquals(List.of("first", "second", "third"), executionOrder);
    }

    @Test
    void submit_differentTopicsAreIndependent() throws Exception {
        List<String> topicAOrder = Collections.synchronizedList(new ArrayList<>());
        List<String> topicBOrder = Collections.synchronizedList(new ArrayList<>());
        
        // Generate tokens
        String tokenA1 = tokenService.generateToken();
        String tokenA2 = tokenService.generateToken();
        String tokenB1 = tokenService.generateToken();
        String tokenB2 = tokenService.generateToken();
        
        // Submit in reverse order for topic-a
        CompletableFuture<Void> futureA2 = processor.submit("topic-a", tokenA2, 
            testJob("a2", params -> {
                topicAOrder.add("A2");
                return null;
            }), Collections.emptyMap());
        CompletableFuture<Void> futureA1 = processor.submit("topic-a", tokenA1, 
            testJob("a1", params -> {
                topicAOrder.add("A1");
                return null;
            }), Collections.emptyMap());
        
        // Submit in reverse order for topic-b
        CompletableFuture<Void> futureB2 = processor.submit("topic-b", tokenB2, 
            testJob("b2", params -> {
                topicBOrder.add("B2");
                return null;
            }), Collections.emptyMap());
        CompletableFuture<Void> futureB1 = processor.submit("topic-b", tokenB1, 
            testJob("b1", params -> {
                topicBOrder.add("B1");
                return null;
            }), Collections.emptyMap());
        
        CompletableFuture.allOf(futureA1, futureA2, futureB1, futureB2).get(5, TimeUnit.SECONDS);
        
        assertEquals(List.of("A1", "A2"), topicAOrder);
        assertEquals(List.of("B1", "B2"), topicBOrder);
    }

    @Test
    void submit_throwsForNullTopic() {
        String token = tokenService.generateToken();
        
        assertThrows(IllegalArgumentException.class, 
            () -> processor.submit(null, token, testJob("test", p -> "result"), Collections.emptyMap()));
    }

    @Test
    void submit_throwsForEmptyTopic() {
        String token = tokenService.generateToken();
        
        assertThrows(IllegalArgumentException.class, 
            () -> processor.submit("", token, testJob("test", p -> "result"), Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, 
            () -> processor.submit("   ", token, testJob("test", p -> "result"), Collections.emptyMap()));
    }

    @Test
    void submit_throwsForNullToken() {
        assertThrows(IllegalArgumentException.class, 
            () -> processor.submit("topic", null, testJob("test", p -> "result"), Collections.emptyMap()));
    }

    @Test
    void submit_throwsForInvalidToken() {
        assertThrows(IllegalArgumentException.class, 
            () -> processor.submit("topic", "invalid-token", testJob("test", p -> "result"), Collections.emptyMap()));
    }

    @Test
    void submit_throwsForTamperedToken() {
        String token = tokenService.generateToken();
        String[] parts = token.split("\\.", 2);
        String tamperedToken = (Long.parseLong(parts[0]) + 1000) + "." + parts[1];
        
        assertThrows(IllegalArgumentException.class, 
            () -> processor.submit("topic", tamperedToken, testJob("test", p -> "result"), Collections.emptyMap()));
    }

    @Test
    void submit_throwsForNullJob() {
        String token = tokenService.generateToken();
        
        assertThrows(IllegalArgumentException.class, 
            () -> processor.submit("topic", token, null, Collections.emptyMap()));
    }

    @Test
    void submit_jobExceptionPropagates() {
        String token = tokenService.generateToken();
        RuntimeException expectedException = new RuntimeException("Job failed");
        
        CompletableFuture<String> future = processor.submit("topic", token, 
            testJob("failing", params -> {
                throw expectedException;
            }), Collections.emptyMap());
        
        ExecutionException ex = assertThrows(ExecutionException.class, 
            () -> future.get(5, TimeUnit.SECONDS));
        assertEquals(expectedException, ex.getCause());
    }

    @Test
    void getPendingCount_returnsCorrectCount() throws Exception {
        CountDownLatch jobStarted = new CountDownLatch(1);
        CountDownLatch jobCanFinish = new CountDownLatch(1);
        
        String token1 = tokenService.generateToken();
        String token2 = tokenService.generateToken();
        
        // First job blocks
        processor.submit("topic", token1, testJob("blocking", params -> {
            jobStarted.countDown();
            try {
                jobCanFinish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }), Collections.emptyMap());
        
        // Wait for first job to start
        jobStarted.await(5, TimeUnit.SECONDS);
        
        // Submit second job - should be pending
        processor.submit("topic", token2, testJob("pending", params -> null), Collections.emptyMap());
        
        assertTrue(processor.getPendingCount("topic") >= 1);
        
        jobCanFinish.countDown();
    }

    @Test
    void getTotalPendingCount_sumsAllTopics() throws Exception {
        CountDownLatch jobsStarted = new CountDownLatch(2);
        CountDownLatch jobsCanFinish = new CountDownLatch(1);
        
        String tokenA1 = tokenService.generateToken();
        String tokenA2 = tokenService.generateToken();
        String tokenB1 = tokenService.generateToken();
        String tokenB2 = tokenService.generateToken();
        
        // Block first job in each topic
        processor.submit("topic-a", tokenA1, testJob("blockA", params -> {
            jobsStarted.countDown();
            try {
                jobsCanFinish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }), Collections.emptyMap());
        processor.submit("topic-b", tokenB1, testJob("blockB", params -> {
            jobsStarted.countDown();
            try {
                jobsCanFinish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }), Collections.emptyMap());
        
        jobsStarted.await(5, TimeUnit.SECONDS);
        
        // Submit more jobs
        processor.submit("topic-a", tokenA2, testJob("pendA", params -> null), Collections.emptyMap());
        processor.submit("topic-b", tokenB2, testJob("pendB", params -> null), Collections.emptyMap());
        
        assertTrue(processor.getTotalPendingCount() >= 2);
        
        jobsCanFinish.countDown();
    }

    @Test
    void shutdown_rejectsNewJobs() {
        processor.shutdown();
        
        String token = tokenService.generateToken();
        CompletableFuture<String> future = processor.submit("topic", token, 
            testJob("test", params -> "result"), Collections.emptyMap());
        
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void isShutdown_returnsTrueAfterShutdown() {
        assertFalse(processor.isShutdown());
        
        processor.shutdown();
        
        assertTrue(processor.isShutdown());
    }

    @Test
    void submit_jobTimesOutAndIsCancelled() throws Exception {
        // Create a processor with very short timeout
        OrderedJobProcessor shortTimeoutProcessor = new OrderedJobProcessor();
        
        Field tokenServiceField = OrderedJobProcessor.class.getDeclaredField("tokenService");
        tokenServiceField.setAccessible(true);
        tokenServiceField.set(shortTimeoutProcessor, tokenService);
        
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
                    return 1L; // 1 second timeout
                }
                return null;
            }
        );
        activateMethod.invoke(shortTimeoutProcessor, configProxy);
        
        try {
            String token = tokenService.generateToken();
            
            CompletableFuture<String> future = shortTimeoutProcessor.submit("topic", token, 
                new GuardedJob<String>() {
                    @Override
                    public String getName() {
                        return "slow-job";
                    }
                    
                    @Override
                    public String execute(Map<String, Object> parameters) throws Exception {
                        Thread.sleep(10000); // Sleep for 10 seconds - will be interrupted
                        return "completed";
                    }
                }, Collections.emptyMap());
            
            // Should throw TimeoutException wrapped in ExecutionException
            ExecutionException ex = assertThrows(ExecutionException.class, 
                () -> future.get(5, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof TimeoutException);
            assertTrue(ex.getCause().getMessage().contains("cancelled"));
        } finally {
            shortTimeoutProcessor.shutdownNow();
        }
    }

    @Test
    void submit_manyJobsProcessInTokenOrder() throws Exception {
        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());
        List<String> tokens = new ArrayList<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        // Generate 100 tokens in order
        for (int i = 0; i < 100; i++) {
            tokens.add(tokenService.generateToken());
        }
        
        // Shuffle and submit
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            indices.add(i);
        }
        Collections.shuffle(indices);
        
        for (int idx : indices) {
            final int index = idx;
            futures.add(processor.submit("topic", tokens.get(idx), 
                testJob("job" + idx, params -> {
                    executionOrder.add(index);
                    return null;
                }), Collections.emptyMap()));
        }
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            expected.add(i);
        }
        assertEquals(expected, executionOrder);
    }

    /**
     * Helper to create a test GuardedJob.
     */
    private <T> GuardedJob<T> testJob(String name, Function<Map<String, Object>, T> executor) {
        return new GuardedJob<T>() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public T execute(Map<String, Object> parameters) throws Exception {
                return executor.apply(parameters);
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
