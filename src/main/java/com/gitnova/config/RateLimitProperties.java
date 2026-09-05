package com.gitnova.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Configuration for the shared Redis token buckets applied to HTTP requests. */
@ConfigurationProperties(prefix = "gitnova.rate-limit")
@Validated
public record RateLimitProperties(
        boolean enabled,
        boolean failOpen,
        @Valid @NotNull BucketPolicy user,
        @Valid @NotNull BucketPolicy repository,
        @Valid @NotNull BucketPolicy api
) {

    public enum Dimension {
        USER,
        REPOSITORY,
        API
    }

    public RateLimitProperties {
        if (user == null || repository == null || api == null) {
            throw new IllegalArgumentException("all rate-limit bucket policies are required");
        }
    }

    public BucketPolicy policyFor(Dimension dimension) {
        return switch (dimension) {
            case USER -> user;
            case REPOSITORY -> repository;
            case API -> api;
        };
    }

    /** One token bucket policy. Tokens are request permits, not LLM usage tokens. */
    public record BucketPolicy(
            @Min(1) long capacityPermits,
            @DecimalMin(value = "0.0", inclusive = false) double refillPermitsPerSecond,
            @Min(1) long requestCost,
            @NotNull Duration idleTtl
    ) {
        public BucketPolicy {
            if (capacityPermits <= 0) {
                throw new IllegalArgumentException("capacityPermits must be positive");
            }
            if (!Double.isFinite(refillPermitsPerSecond)
                    || refillPermitsPerSecond <= 0) {
                throw new IllegalArgumentException(
                        "refillPermitsPerSecond must be finite and positive"
                );
            }
            if (requestCost <= 0 || requestCost > capacityPermits) {
                throw new IllegalArgumentException(
                        "requestCost must be positive and no greater than capacityPermits"
                );
            }
            if (idleTtl == null || idleTtl.isZero() || idleTtl.isNegative()) {
                throw new IllegalArgumentException("idleTtl must be positive");
            }
            double refillMillis = capacityPermits * 1_000D / refillPermitsPerSecond;
            if (idleTtl.toMillis() < Math.ceil(refillMillis)) {
                throw new IllegalArgumentException(
                        "idleTtl must be at least the time required to refill the bucket"
                );
            }
        }
    }
}
