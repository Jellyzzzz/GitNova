package com.gitnova.ratelimit;

import com.gitnova.config.RateLimitProperties.BucketPolicy;
import com.gitnova.config.RateLimitProperties.Dimension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Executes one shared Redis token bucket per resolved request dimension. */
@Component
public final class RedisTokenBucketRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> tokenBucketScript;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(
                redisTemplate,
                "redisTemplate must not be null"
        );
        this.tokenBucketScript = new DefaultRedisScript<>();
        this.tokenBucketScript.setLocation(
                new ClassPathResource("redis/token_bucket.lua")
        );
        this.tokenBucketScript.setResultType(List.class);
    }

    public Decision consume(List<BucketRequest> buckets) {
        Objects.requireNonNull(buckets, "buckets must not be null");
        Decision tightest = null;
        for (BucketRequest bucket : buckets) {
            Decision decision = consume(bucket);
            if (!decision.allowed()) {
                return decision;
            }
            if (tightest == null || decision.remainingRatio() < tightest.remainingRatio()) {
                tightest = decision;
            }
        }
        return tightest == null ? Decision.unlimited() : tightest;
    }

    private Decision consume(BucketRequest bucket) {
        BucketPolicy policy = bucket.policy();
        List<?> raw = redisTemplate.execute(
                tokenBucketScript,
                List.of(bucket.redisKey()),
                Long.toString(policy.capacityPermits()),
                Double.toString(policy.refillPermitsPerSecond() / 1_000D),
                Long.toString(policy.requestCost()),
                Long.toString(policy.idleTtl().toMillis())
        );
        if (raw == null || raw.size() != 3) {
            throw new IllegalStateException("Redis token bucket returned an invalid result");
        }
        boolean allowed = numberAt(raw, 0) == 1L;
        long remaining = Math.max(0L, numberAt(raw, 1));
        long retryAfterMillis = Math.max(0L, numberAt(raw, 2));
        return new Decision(
                allowed,
                bucket.dimension(),
                policy.capacityPermits(),
                remaining,
                retryAfterMillis
        );
    }

    private static long numberAt(List<?> values, int index) {
        Object value = values.get(index);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Redis token bucket returned a non-numeric result");
        }
        return number.longValue();
    }

    public record BucketRequest(
            Dimension dimension,
            String redisKey,
            BucketPolicy policy
    ) {
        public BucketRequest {
            Objects.requireNonNull(dimension, "dimension must not be null");
            Objects.requireNonNull(redisKey, "redisKey must not be null");
            Objects.requireNonNull(policy, "policy must not be null");
            if (redisKey.isBlank()) {
                throw new IllegalArgumentException("redisKey must not be blank");
            }
        }
    }

    public record Decision(
            boolean allowed,
            Dimension dimension,
            long limit,
            long remaining,
            long retryAfterMillis
    ) {
        private static Decision unlimited() {
            return new Decision(true, null, Long.MAX_VALUE, Long.MAX_VALUE, 0L);
        }

        double remainingRatio() {
            return limit == Long.MAX_VALUE ? 1D : (double) remaining / limit;
        }

        public long retryAfterSeconds() {
            if (retryAfterMillis <= 0) {
                return 0L;
            }
            return Math.max(1L, (retryAfterMillis + 999L) / 1_000L);
        }
    }
}
