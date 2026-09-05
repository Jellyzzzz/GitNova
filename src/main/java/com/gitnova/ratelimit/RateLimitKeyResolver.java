package com.gitnova.ratelimit;

import com.gitnova.common.UserContext;
import com.gitnova.config.RateLimitProperties.Dimension;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves stable rate-limit identities from an authenticated MVC request. */
@Component
public final class RateLimitKeyResolver {

    private static final String KEY_PREFIX = "gitnova:rate:";

    public List<ResolvedKey> resolve(HttpServletRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<ResolvedKey> keys = new ArrayList<>(3);

        Long userId = UserContext.getUserId();
        if (userId != null && userId > 0) {
            keys.add(new ResolvedKey(
                    Dimension.USER,
                    KEY_PREFIX + "user:" + userId
            ));
        }

        resolveRepositoryId(request).ifPresent(repoId -> keys.add(new ResolvedKey(
                Dimension.REPOSITORY,
                KEY_PREFIX + "repo:" + repoId
        )));

        resolveApiName(request).ifPresent(apiName -> keys.add(new ResolvedKey(
                Dimension.API,
                KEY_PREFIX + "api:" + apiName
        )));
        return List.copyOf(keys);
    }

    private java.util.OptionalLong resolveRepositoryId(HttpServletRequest request) {
        Object attribute = request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE
        );
        if (!(attribute instanceof Map<?, ?> variables)) {
            return java.util.OptionalLong.empty();
        }
        Object rawRepoId = variables.get("repoId");
        if (rawRepoId == null) {
            return java.util.OptionalLong.empty();
        }
        try {
            long repoId = Long.parseLong(rawRepoId.toString());
            return repoId > 0
                    ? java.util.OptionalLong.of(repoId)
                    : java.util.OptionalLong.empty();
        } catch (NumberFormatException ignored) {
            return java.util.OptionalLong.empty();
        }
    }

    private java.util.Optional<String> resolveApiName(HttpServletRequest request) {
        Object attribute = request.getAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE
        );
        if (!(attribute instanceof String routePattern) || routePattern.isBlank()) {
            return java.util.Optional.empty();
        }
        String canonical = request.getMethod() + ":" + routePattern;
        return java.util.Optional.of(canonical.replaceAll("[^A-Za-z0-9._/-]", "_"));
    }

    public record ResolvedKey(Dimension dimension, String redisKey) {
        public ResolvedKey {
            Objects.requireNonNull(dimension, "dimension must not be null");
            Objects.requireNonNull(redisKey, "redisKey must not be null");
            if (redisKey.isBlank()) {
                throw new IllegalArgumentException("redisKey must not be blank");
            }
        }
    }
}
