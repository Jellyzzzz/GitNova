package com.gitnova.interceptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.common.UserContext;
import com.gitnova.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtInterceptorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final JwtInterceptor interceptor = new JwtInterceptor(jwtUtil, objectMapper);

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void shouldLoadUsernameFromTheSignedSubject() throws Exception {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("alice");
        when(claims.get("userId", Long.class)).thenReturn(7L);
        when(jwtUtil.parseToken("valid-token")).thenReturn(claims);
        MockHttpServletRequest request = requestWithBearer("valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(7L, UserContext.getUserId());
        assertEquals("alice", UserContext.getUsername());
    }

    @Test
    void shouldReturnJson401WhenAuthorizationIsMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(
                new MockHttpServletRequest("POST", "/api/repos"),
                response,
                new Object()
        ));

        assertUnauthorized(response);
    }

    @Test
    void shouldReturnSafeJson401ForAnInvalidToken() throws Exception {
        when(jwtUtil.parseToken("invalid-token"))
                .thenThrow(new JwtException("provider detail must not be exposed"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(
                requestWithBearer("invalid-token"),
                response,
                new Object()
        ));

        assertUnauthorized(response);
        assertFalse(response.getContentAsString().contains("provider detail"));
    }

    @Test
    void shouldRejectSignedTokensWithoutRequiredIdentityClaims() throws Exception {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("alice");
        when(claims.get("userId", Long.class)).thenReturn(null);
        when(jwtUtil.parseToken("incomplete-token")).thenReturn(claims);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(
                requestWithBearer("incomplete-token"),
                response,
                new Object()
        ));

        assertUnauthorized(response);
    }

    private static MockHttpServletRequest requestWithBearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/repos");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private void assertUnauthorized(MockHttpServletResponse response) throws Exception {
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(401, body.path("code").asInt());
        assertEquals("身份认证失败", body.path("message").asText());
    }
}
