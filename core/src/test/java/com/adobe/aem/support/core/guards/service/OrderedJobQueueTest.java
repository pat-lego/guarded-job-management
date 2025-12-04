package com.adobe.aem.support.core.guards.service;

import com.adobe.aem.support.core.guards.token.GuardedOrderToken;
import com.adobe.aem.support.core.guards.token.GuardedOrderTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class OrderedJobQueueTest {

    private static final String SECRET_KEY = "test-secret-key";
    private GuardedOrderTokenService tokenService;
    private OrderedJobQueue queue;

    @BeforeEach
    void setUp() {
        tokenService = new TestTokenService(SECRET_KEY);
        queue = new OrderedJobQueue(tokenService);
    }

    @Test
    void add_returnsCompletableFuture() {
        String token = tokenService.generateToken();
        
        CompletableFuture<String> future = queue.add(token, testJob("test", p -> "result"), Collections.emptyMap());
        
        assertNotNull(future);
        assertFalse(future.isDone());
    }

    @Test
    void add_incrementsSize() {
        assertEquals(0, queue.size());
        
        queue.add(tokenService.generateToken(), testJob("a", p -> "a"), Collections.emptyMap());
        assertEquals(1, queue.size());
        
        queue.add(tokenService.generateToken(), testJob("b", p -> "b"), Collections.emptyMap());
        assertEquals(2, queue.size());
    }

    @Test
    void add_throwsForInvalidToken() {
        assertThrows(IllegalArgumentException.class, 
            () -> queue.add("invalid-token", testJob("test", p -> "result"), Collections.emptyMap()));
    }

    @Test
    void poll_returnsNullWhenEmpty() {
        assertNull(queue.poll());
    }

    @Test
    void poll_returnsJobEntry() {
        String token = tokenService.generateToken();
        queue.add(token, testJob("test", p -> "result"), Collections.emptyMap());
        
        OrderedJobQueue.JobEntry<?> entry = queue.poll();
        
        assertNotNull(entry);
        assertNotNull(entry.getJob());
        assertNotNull(entry.getFuture());
    }

    @Test
    void poll_decrementsSize() {
        queue.add(tokenService.generateToken(), testJob("a", p -> "a"), Collections.emptyMap());
        queue.add(tokenService.generateToken(), testJob("b", p -> "b"), Collections.emptyMap());
        assertEquals(2, queue.size());
        
        queue.poll();
        assertEquals(1, queue.size());
        
        queue.poll();
        assertEquals(0, queue.size());
    }

    @Test
    void poll_returnsJobsInTokenOrder() {
        // Generate tokens in order
        String token1 = tokenService.generateToken();
        String token2 = tokenService.generateToken();
        String token3 = tokenService.generateToken();
        
        // Add in reverse order
        queue.add(token3, testJob("third", p -> "third"), Collections.emptyMap());
        queue.add(token1, testJob("first", p -> "first"), Collections.emptyMap());
        queue.add(token2, testJob("second", p -> "second"), Collections.emptyMap());
        
        // Poll should return in token order
        OrderedJobQueue.JobEntry<?> entry1 = queue.poll();
        OrderedJobQueue.JobEntry<?> entry2 = queue.poll();
        OrderedJobQueue.JobEntry<?> entry3 = queue.poll();
        
        entry1.execute();
        entry2.execute();
        entry3.execute();
        
        assertEquals("first", entry1.getFuture().join());
        assertEquals("second", entry2.getFuture().join());
        assertEquals("third", entry3.getFuture().join());
    }

    @Test
    void isEmpty_returnsTrueWhenEmpty() {
        assertTrue(queue.isEmpty());
    }

    @Test
    void isEmpty_returnsFalseWhenNotEmpty() {
        queue.add(tokenService.generateToken(), testJob("a", p -> "a"), Collections.emptyMap());
        assertFalse(queue.isEmpty());
    }

    @Test
    void isEmpty_returnsTrueAfterPollingAll() {
        queue.add(tokenService.generateToken(), testJob("a", p -> "a"), Collections.emptyMap());
        queue.poll();
        assertTrue(queue.isEmpty());
    }

    @Test
    void jobEntry_execute_completesFutureWithResult() throws Exception {
        String token = tokenService.generateToken();
        CompletableFuture<String> future = queue.add(token, testJob("hello", p -> "hello"), Collections.emptyMap());
        
        OrderedJobQueue.JobEntry<?> entry = queue.poll();
        entry.execute();
        
        assertTrue(future.isDone());
        assertEquals("hello", future.get());
    }

    @Test
    void jobEntry_execute_completesFutureExceptionallyOnError() {
        String token = tokenService.generateToken();
        RuntimeException expectedException = new RuntimeException("job failed");
        CompletableFuture<String> future = queue.add(token, testJob("failing", p -> {
            throw expectedException;
        }), Collections.emptyMap());
        
        OrderedJobQueue.JobEntry<?> entry = queue.poll();
        entry.execute();
        
        assertTrue(future.isCompletedExceptionally());
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertEquals(expectedException, ex.getCause());
    }

    @Test
    void manyJobs_pollInCorrectOrder() {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tokens.add(tokenService.generateToken());
        }
        
        // Shuffle the order we add them
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            indices.add(i);
        }
        Collections.shuffle(indices);
        
        // Add in shuffled order
        for (int idx : indices) {
            final int index = idx;
            queue.add(tokens.get(idx), testJob("job" + idx, p -> index), Collections.emptyMap());
        }
        
        // Poll should return in original token order (0, 1, 2, ...)
        for (int i = 0; i < 100; i++) {
            OrderedJobQueue.JobEntry<?> entry = queue.poll();
            entry.execute();
            assertEquals(i, entry.getFuture().join());
        }
    }

    @Test
    void concurrentAddAndPoll_threadSafe() throws Exception {
        int numProducers = 4;
        int jobsPerProducer = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numProducers + 1);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numProducers);
        List<CompletableFuture<Integer>> allFutures = new CopyOnWriteArrayList<>();
        
        // Producers add jobs
        for (int p = 0; p < numProducers; p++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < jobsPerProducer; i++) {
                        String token = tokenService.generateToken();
                        CompletableFuture<Integer> future = queue.add(token, testJob("job", params -> 1), Collections.emptyMap());
                        allFutures.add(future);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        // Consumer polls and executes
        executor.submit(() -> {
            try {
                startLatch.await();
                int processed = 0;
                while (processed < numProducers * jobsPerProducer) {
                    OrderedJobQueue.JobEntry<?> entry = queue.poll();
                    if (entry != null) {
                        entry.execute();
                        processed++;
                    } else {
                        Thread.yield();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        
        // Wait for all futures to complete
        CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0]))
            .get(10, TimeUnit.SECONDS);
        
        // All jobs should have completed successfully
        int sum = allFutures.stream()
            .mapToInt(CompletableFuture::join)
            .sum();
        assertEquals(numProducers * jobsPerProducer, sum);
        assertTrue(queue.isEmpty());
        
        executor.shutdown();
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
     * Test implementation of GuardedOrderTokenService that delegates to GuardedOrderToken.
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
