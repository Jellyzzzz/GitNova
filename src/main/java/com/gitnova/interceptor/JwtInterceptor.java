package com.gitnova.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.common.UserContext;
import com.gitnova.dto.ApiResponse;
import com.gitnova.util.JwtUtil;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public JwtInterceptor(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) throws IOException {

        // 防止线程池复用时残留上一次请求的身份信息
        UserContext.clear();

        // 保留现有的 OPTIONS 预检处理
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String authorization = request.getHeader("Authorization");

        if (authorization == null ||
                !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return rejectUnauthorized(response);
        }

        String token = authorization.substring(7).trim();

        if (token.isEmpty()) {
            return rejectUnauthorized(response);
        }

        try {
            // JwtUtil 负责签名、有效期和身份声明的验证
            Long userId = jwtUtil.verifyAndGetUserId(token);

            if (userId == null || userId <= 0) {
                return rejectUnauthorized(response);
            }

            // 认证成功后才写入当前线程的用户上下文
            UserContext.setUserId(userId);

            return true;

        } catch (JwtException | IllegalArgumentException ex) {
            return rejectUnauthorized(response);
        }
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex
    ) {
        UserContext.clear();
    }

    private boolean rejectUnauthorized(
            HttpServletResponse response
    ) throws IOException {

        UserContext.clear();

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.error(401, "身份认证失败")
        );

        return false;
    }
}
