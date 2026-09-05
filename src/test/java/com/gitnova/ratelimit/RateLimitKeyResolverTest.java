package com.gitnova.ratelimit;

import com.gitnova.common.UserContext;
import com.gitnova.config.RateLimitProperties.Dimension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitKeyResolverTest {

    private final RateLimitKeyResolver resolver = new RateLimitKeyResolver();

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void shouldResolveUserRepositoryAndNormalizedApiKeys() {
        UserContext.setUserId(7L);
        MockHttpServletRequest request = request(
                "POST",
                "/api/repos/42/push/transfer",
                "/api/repos/{repoId}/push/transfer",
                Map.of("repoId", "42")
        );

        List<RateLimitKeyResolver.ResolvedKey> keys = resolver.resolve(request);

        assertEquals(List.of(
                new RateLimitKeyResolver.ResolvedKey(
                        Dimension.USER,
                        "gitnova:rate:user:7"
                ),
                new RateLimitKeyResolver.ResolvedKey(
                        Dimension.REPOSITORY,
                        "gitnova:rate:repo:42"
                ),
                new RateLimitKeyResolver.ResolvedKey(
                        Dimension.API,
                        "gitnova:rate:api:POST_/api/repos/_repoId_/push/transfer"
                )
        ), keys);
    }

    @Test
    void shouldNotUseRawRequestUriAsApiIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/repos/999/private-value"
        );

        assertEquals(List.of(), resolver.resolve(request));
    }

    @Test
    void shouldIgnoreInvalidRepositoryIdentity() {
        MockHttpServletRequest request = request(
                "GET",
                "/api/repos/not-a-number",
                "/api/repos/{repoId}",
                Map.of("repoId", "not-a-number")
        );

        assertEquals(List.of(
                new RateLimitKeyResolver.ResolvedKey(
                        Dimension.API,
                        "gitnova:rate:api:GET_/api/repos/_repoId_"
                )
        ), resolver.resolve(request));
    }

    private static MockHttpServletRequest request(
            String method,
            String uri,
            String routePattern,
            Map<String, String> variables
    ) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                routePattern
        );
        request.setAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                variables
        );
        return request;
    }
}
