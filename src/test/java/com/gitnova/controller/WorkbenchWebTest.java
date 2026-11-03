package com.gitnova.controller;

import com.gitnova.config.JwtInterceptorConfig;
import com.gitnova.config.GlobalExceptionHandler;
import io.jsonwebtoken.Claims;
import com.gitnova.interceptor.JwtInterceptor;
import com.gitnova.ratelimit.RateLimitInterceptor;
import com.gitnova.service.RepoService;
import com.gitnova.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Spring MVC resource routing and JWT boundary, without external infrastructure. */
@WebMvcTest(controllers = RepoController.class)
@ContextConfiguration(classes = {RepoController.class, JwtInterceptorConfig.class, JwtInterceptor.class, GlobalExceptionHandler.class})
class WorkbenchWebTest {
    @Autowired MockMvc mvc;
    @MockitoBean RepoService repoService;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean RateLimitInterceptor rateLimitInterceptor;

    @Test
    void shouldServeTheLoginPageAndAssetsWithoutAuthentication() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk());
        String html = mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(html.contains("登录你的工作台"));
        assertTrue(html.contains("id=\"agent-unavailable\""));
        assertFalse(html.contains("id=\"task-form\""));
        assertFalse(html.contains("id=\"session-form\""));
        for (String asset : new String[] {"/app.css", "/app.js", "/api.js"}) {
            mvc.perform(get(asset)).andExpect(status().isOk());
        }
        verifyNoInteractions(jwtUtil, rateLimitInterceptor, repoService);
    }

    @Test
    void shouldStillRequireAuthenticationForRepositoryApis() throws Exception {
        mvc.perform(get("/api/repos"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(repoService, rateLimitInterceptor);
    }
    @Test
    void shouldReturnNotFoundForRetiredAgentRoutes() throws Exception {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("developer");
        when(claims.get("userId", Long.class)).thenReturn(1L);
        when(jwtUtil.parseToken("test-token")).thenReturn(claims);
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        mvc.perform(get("/api/repos/42/agent/sessions")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        for (String path : new String[] {
                "/api/repos/42/agent/sessions",
                "/api/repos/42/agent/sessions/old-session/tasks"
        }) {
            mvc.perform(post(path)
                            .header("Authorization", "Bearer test-token")
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404));
        }
        verifyNoInteractions(repoService);
    }

}
