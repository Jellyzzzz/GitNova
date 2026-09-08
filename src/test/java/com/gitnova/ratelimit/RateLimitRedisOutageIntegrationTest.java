package com.gitnova.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.config.RateLimitProperties;
import com.gitnova.config.RateLimitProperties.BucketPolicy;
import com.gitnova.config.RateLimitProperties.Dimension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises the configured failure policy through a real Redis client connection failure. */
@Tag("redis-it")
class RateLimitRedisOutageIntegrationTest {

    @Test
    void shouldFailOpenWhenRedisEndpointCannotBeReached() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = invokeAgainstUnavailableRedis(true, response);

        assertTrue(allowed);
        assertEquals(200, response.getStatus());
    }

    @Test
    void shouldFailClosedWhenRedisEndpointCannotBeReached() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = invokeAgainstUnavailableRedis(false, response);

        assertFalse(allowed);
        assertEquals(503, response.getStatus());
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
        assertEquals(503, body.path("code").asInt());
    }

    private static boolean invokeAgainstUnavailableRedis(
            boolean failOpen,
            MockHttpServletResponse response
    ) throws Exception {
        RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration(
                "127.0.0.1",
                1
        );
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redis);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        try {
            StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
            redisTemplate.afterPropertiesSet();
            RateLimitKeyResolver resolver = mock(RateLimitKeyResolver.class);
            MockHttpServletRequest request = new MockHttpServletRequest(
                    "GET",
                    "/api/repos"
            );
            when(resolver.resolve(request)).thenReturn(List.of(
                    new RateLimitKeyResolver.ResolvedKey(
                            Dimension.API,
                            "gitnova:rate:api:get-repos"
                    )
            ));
            BucketPolicy policy = new BucketPolicy(
                    1,
                    1D,
                    1,
                    Duration.ofSeconds(1)
            );
            RateLimitInterceptor interceptor = new RateLimitInterceptor(
                    new RateLimitProperties(
                            true,
                            failOpen,
                            policy,
                            policy,
                            policy
                    ),
                    resolver,
                    new RedisTokenBucketRateLimiter(redisTemplate),
                    new ObjectMapper()
            );
            return interceptor.preHandle(request, response, new Object());
        } finally {
            connectionFactory.destroy();
        }
    }
}
