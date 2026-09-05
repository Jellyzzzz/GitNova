package com.gitnova.ratelimit;

import com.gitnova.config.RateLimitProperties.BucketPolicy;
import com.gitnova.config.RateLimitProperties.Dimension;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisTokenBucketRateLimiterTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldReturnMostConstrainedAllowedBucket() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(List.of(1L, 50L, 0L), List.of(1L, 10L, 0L));
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(
                redisTemplate
        );

        RedisTokenBucketRateLimiter.Decision decision = limiter.consume(List.of(
                bucket(Dimension.USER, "user", 100),
                bucket(Dimension.API, "api", 200)
        ));

        assertTrue(decision.allowed());
        assertEquals(Dimension.API, decision.dimension());
        assertEquals(200L, decision.limit());
        assertEquals(10L, decision.remaining());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldStopAtRejectedBucketAndRoundRetryAfterUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(List.of(0L, 0L, 1_001L));
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(
                redisTemplate
        );

        RedisTokenBucketRateLimiter.Decision decision = limiter.consume(List.of(
                bucket(Dimension.REPOSITORY, "repo", 10)
        ));

        assertFalse(decision.allowed());
        assertEquals(Dimension.REPOSITORY, decision.dimension());
        assertEquals(2L, decision.retryAfterSeconds());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldRejectMalformedLuaResult() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(List.of(1L));
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(
                redisTemplate
        );

        assertThrows(
                IllegalStateException.class,
                () -> limiter.consume(List.of(bucket(Dimension.USER, "user", 10)))
        );
    }

    private static RedisTokenBucketRateLimiter.BucketRequest bucket(
            Dimension dimension,
            String suffix,
            long capacity
    ) {
        return new RedisTokenBucketRateLimiter.BucketRequest(
                dimension,
                "gitnova:rate:" + suffix,
                new BucketPolicy(capacity, 10D, 1L, Duration.ofMinutes(10))
        );
    }
}
