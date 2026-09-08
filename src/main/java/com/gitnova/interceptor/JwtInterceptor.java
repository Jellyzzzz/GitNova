package com.gitnova.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.common.UserContext;
import com.gitnova.dto.ApiResponse;
import com.gitnova.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * JWT 认证拦截器
 *
 * 拦截除 /api/auth/** 以外的所有请求：
 * 1. 从 Header Authorization: Bearer <token> 取 token
 * 2. 校验签名 → 解析 userId → 放入 UserContext (ThreadLocal)
 * 3. 失败直接返回 401
 *
 * ⚠️ 必须在 afterCompletion 中调用 UserContext.clear() 清理 ThreadLocal
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public JwtInterceptor(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = Objects.requireNonNull(jwtUtil, "jwtUtil must not be null");
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return rejectUnauthorized(response);
        }

        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            return rejectUnauthorized(response);
        }

        try {
            Claims claims = jwtUtil.parseToken(token);
            String username = claims.getSubject();
            Long userId = claims.get("userId", Long.class);
            if (username == null || username.isBlank() || userId == null || userId <= 0) {
                return rejectUnauthorized(response);
            }

            UserContext.setUsername(username);
            UserContext.setUserId(userId);
            return true;
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException exception) {
            return rejectUnauthorized(response);
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // ⚠️ 关键：防止线程池复用导致 ThreadLocal 数据污染
        UserContext.clear();
    }

    private boolean rejectUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.error(HttpServletResponse.SC_UNAUTHORIZED, "身份认证失败")
        );
        return false;
    }
}
