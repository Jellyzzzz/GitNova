package com.gitnova.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.common.UserContext;
import com.gitnova.config.RateLimitProperties;
import com.gitnova.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Applies shared user, repository and normalized API token buckets. */
@Component
public final class RateLimitInterceptor implements HandlerInterceptor {

    static final String LIMIT_HEADER = "X-RateLimit-Limit";
    static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    static final String DIMENSION_HEADER = "X-RateLimit-Dimension";
    static final String RETRY_AFTER_HEADER = "Retry-After";

    private static final Logger logger = LoggerFactory.getLogger(
            RateLimitInterceptor.class
    );

    private final RateLimitProperties properties;
    private final RateLimitKeyResolver keyResolver;
    private final RedisTokenBucketRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(
            RateLimitProperties properties,
            RateLimitKeyResolver keyResolver,
            RedisTokenBucketRateLimiter rateLimiter,
            ObjectMapper objectMapper
    ) {
        this.properties = Objects.requireNonNull(
                properties,
                "properties must not be null"
        );
        this.keyResolver = Objects.requireNonNull(
                keyResolver,
                "keyResolver must not be null"
        );
        this.rateLimiter = Objects.requireNonNull(
                rateLimiter,
                "rateLimiter must not be null"
        );
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) throws IOException {
        if (!properties.enabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        try {
            RedisTokenBucketRateLimiter.Decision decision = rateLimiter.consume(
                    buildBuckets(keyResolver.resolve(request))
            );
            writeRateLimitHeaders(response, decision);
            if (decision.allowed()) {
                return true;
            }
            reject(response, decision);
            return false;
        } catch (DataAccessException exception) {
            return onRedisUnavailable(request, response, exception);
        }
    }

    private List<RedisTokenBucketRateLimiter.BucketRequest> buildBuckets(
            List<RateLimitKeyResolver.ResolvedKey> keys
    ) {
        return keys.stream()
                .map(key -> new RedisTokenBucketRateLimiter.BucketRequest(
                        key.dimension(),
                        key.redisKey(),
                        properties.policyFor(key.dimension())
                ))
                .toList();
    }

    private void writeRateLimitHeaders(
            HttpServletResponse response,
            RedisTokenBucketRateLimiter.Decision decision
    ) {
        if (decision.dimension() == null) {
            return;
        }
        response.setHeader(LIMIT_HEADER, Long.toString(decision.limit()));
        response.setHeader(REMAINING_HEADER, Long.toString(decision.remaining()));
        response.setHeader(DIMENSION_HEADER, decision.dimension().name().toLowerCase());
    }

    private void reject(
            HttpServletResponse response,
            RedisTokenBucketRateLimiter.Decision decision
    ) throws IOException {
        response.setStatus(429);
        response.setHeader(
                RETRY_AFTER_HEADER,
                Long.toString(decision.retryAfterSeconds())
        );
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.error(429, "请求过于频繁，请稍后重试")
        );
    }

    private boolean onRedisUnavailable(
            HttpServletRequest request,
            HttpServletResponse response,
            DataAccessException exception
    ) throws IOException {
        logger.warn(
                "Redis rate limiter unavailable: method={}, uri={}, userId={}, failOpen={}, "
                        + "error={}",
                request.getMethod(),
                request.getRequestURI(),
                UserContext.getUserId(),
                properties.failOpen(),
                exception.getMessage()
        );
        if (properties.failOpen()) {
            return true;
        }
        response.setStatus(503);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.error(503, "限流服务暂时不可用")
        );
        return false;
    }
}
