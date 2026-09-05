package com.gitnova.ratelimit;

import com.gitnova.config.RateLimitProperties.BucketPolicy;
import com.gitnova.config.RateLimitProperties.Dimension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("redis-it")
class RedisTokenBucketRateLimiterIntegrationTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void connectToRedis() {
        String host = System.getProperty(
                "redis.host",
                System.getenv().getOrDefault("REDIS_HOST", "localhost")
        );
        int port = Integer.parseInt(System.getProperty(
                "redis.port",
                System.getenv().getOrDefault("REDIS_PORT", "6379")
        ));
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(host, port);
        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            configuration.setPassword(password);
        }
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void closeRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldShareOneAtomicCapacityAcrossLimiterInstances() throws Exception {
        String key = uniqueKey("concurrency");
        RedisTokenBucketRateLimiter first = new RedisTokenBucketRateLimiter(redisTemplate);
        RedisTokenBucketRateLimiter second = new RedisTokenBucketRateLimiter(redisTemplate);
        BucketPolicy policy = new BucketPolicy(
                25,
                0.1D,
                1,
                Duration.ofHours(1)
        );
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                RedisTokenBucketRateLimiter limiter = index % 2 == 0 ? first : second;
                calls.add(() -> {
                    start.await();
                    return limiter.consume(List.of(bucket(key, policy))).allowed();
                });
            }
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> call : calls) {
                futures.add(executor.submit(call));
            }
            start.countDown();

            int allowed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(5, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }

            assertEquals(25, allowed);
            Long ttl = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
            assertNotNull(ttl);
            assertTrue(ttl > 0);
            assertTrue(ttl <= Duration.ofHours(1).toMillis());
        } finally {
            executor.shutdownNow();
            redisTemplate.delete(key);
        }
    }

    @Test
    void shouldRejectEmptyBucketThenRefillUsingRedisTime() throws Exception {
        String key = uniqueKey("refill");
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(redisTemplate);
        BucketPolicy policy = new BucketPolicy(
                2,
                10D,
                1,
                Duration.ofMinutes(1)
        );
        try {
            assertTrue(limiter.consume(List.of(bucket(key, policy))).allowed());
            assertTrue(limiter.consume(List.of(bucket(key, policy))).allowed());
            assertFalse(limiter.consume(List.of(bucket(key, policy))).allowed());

            Thread.sleep(150);

            assertTrue(limiter.consume(List.of(bucket(key, policy))).allowed());
        } finally {
            redisTemplate.delete(key);
        }
    }

    private static RedisTokenBucketRateLimiter.BucketRequest bucket(
            String key,
            BucketPolicy policy
    ) {
        return new RedisTokenBucketRateLimiter.BucketRequest(
                Dimension.USER,
                key,
                policy
        );
    }

    private static String uniqueKey(String suffix) {
        return "gitnova:test:rate:" + suffix + ":" + UUID.randomUUID();
    }
}
