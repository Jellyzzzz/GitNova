package com.gitnova.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.config.RateLimitProperties;
import com.gitnova.config.RateLimitProperties.BucketPolicy;
import com.gitnova.config.RateLimitProperties.Dimension;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitInterceptorTest {

    private static final Object HANDLER = new Object();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSkipRedisWhenDisabled() throws Exception {
        RateLimitKeyResolver resolver = mock(RateLimitKeyResolver.class);
        RedisTokenBucketRateLimiter limiter = mock(RedisTokenBucketRateLimiter.class);
        RateLimitInterceptor interceptor = interceptor(false, true, resolver, limiter);

        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/repos"),
                new MockHttpServletResponse(),
                HANDLER
        );

        assertTrue(allowed);
        verify(resolver, never()).resolve(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldExposeMostConstrainedAllowedBucket() throws Exception {
        RateLimitKeyResolver resolver = mock(RateLimitKeyResolver.class);
        RedisTokenBucketRateLimiter limiter = mock(RedisTokenBucketRateLimiter.class);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/repos"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        List<RateLimitKeyResolver.ResolvedKey> keys = List.of(
                new RateLimitKeyResolver.ResolvedKey(Dimension.USER, "user:7")
        );
        when(resolver.resolve(request)).thenReturn(keys);
        when(limiter.consume(org.mockito.ArgumentMatchers.anyList())).thenReturn(
                new RedisTokenBucketRateLimiter.Decision(
                        true,
                        Dimension.USER,
                        120,
                        17,
                        0
                )
        );

        boolean allowed = interceptor(true, true, resolver, limiter)
                .preHandle(request, response, HANDLER);

        assertTrue(allowed);
        assertEquals("120", response.getHeader(RateLimitInterceptor.LIMIT_HEADER));
        assertEquals("17", response.getHeader(RateLimitInterceptor.REMAINING_HEADER));
        assertEquals("user", response.getHeader(RateLimitInterceptor.DIMENSION_HEADER));
    }

    @Test
    void shouldRejectWith429AndRetryAfter() throws Exception {
        RateLimitKeyResolver resolver = mock(RateLimitKeyResolver.class);
        RedisTokenBucketRateLimiter limiter = mock(RedisTokenBucketRateLimiter.class);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/repos/42"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(resolver.resolve(request)).thenReturn(List.of(
                new RateLimitKeyResolver.ResolvedKey(Dimension.REPOSITORY, "repo:42")
        ));
        when(limiter.consume(org.mockito.ArgumentMatchers.anyList())).thenReturn(
                new RedisTokenBucketRateLimiter.Decision(
                        false,
                        Dimension.REPOSITORY,
                        10,
                        0,
                        1_001
                )
        );

        boolean allowed = interceptor(true, true, resolver, limiter)
                .preHandle(request, response, HANDLER);

        assertFalse(allowed);
        assertEquals(429, response.getStatus());
        assertEquals("2", response.getHeader(RateLimitInterceptor.RETRY_AFTER_HEADER));
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(429, body.path("code").asInt());
    }

    @Test
    void shouldFailOpenWhenRedisIsUnavailable() throws Exception {
        RateLimitKeyResolver resolver = mock(RateLimitKeyResolver.class);
        RedisTokenBucketRateLimiter limiter = mock(RedisTokenBucketRateLimiter.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/repos");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(resolver.resolve(request)).thenReturn(List.of(
                new RateLimitKeyResolver.ResolvedKey(Dimension.API, "api:get-repos")
        ));
        when(limiter.consume(org.mockito.ArgumentMatchers.anyList())).thenThrow(
                new RedisConnectionFailureException("redis unavailable")
        );

        boolean allowed = interceptor(true, true, resolver, limiter)
                .preHandle(request, response, HANDLER);

        assertTrue(allowed);
        assertEquals(200, response.getStatus());
        assertNull(response.getHeader(RateLimitInterceptor.LIMIT_HEADER));
    }

    @Test
    void shouldFailClosedWhenConfigured() throws Exception {
        RateLimitKeyResolver resolver = mock(RateLimitKeyResolver.class);
        RedisTokenBucketRateLimiter limiter = mock(RedisTokenBucketRateLimiter.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/repos");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(resolver.resolve(request)).thenReturn(List.of(
                new RateLimitKeyResolver.ResolvedKey(Dimension.API, "api:get-repos")
        ));
        when(limiter.consume(org.mockito.ArgumentMatchers.anyList())).thenThrow(
                new RedisConnectionFailureException("redis unavailable")
        );

        boolean allowed = interceptor(true, false, resolver, limiter)
                .preHandle(request, response, HANDLER);

        assertFalse(allowed);
        assertEquals(503, response.getStatus());
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(503, body.path("code").asInt());
    }

    private RateLimitInterceptor interceptor(
            boolean enabled,
            boolean failOpen,
            RateLimitKeyResolver resolver,
            RedisTokenBucketRateLimiter limiter
    ) {
        BucketPolicy policy = new BucketPolicy(
                120,
                2D,
                1,
                Duration.ofMinutes(10)
        );
        return new RateLimitInterceptor(
                new RateLimitProperties(
                        enabled,
                        failOpen,
                        policy,
                        policy,
                        policy
                ),
                resolver,
                limiter,
                objectMapper
        );
    }
}
