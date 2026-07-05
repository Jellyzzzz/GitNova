package com.gitnova.interceptor;

import com.gitnova.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

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

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        // TODO: Phase 1 — JWT 校验逻辑
        // 1. 从 Header 取 Authorization: Bearer <token>
        // 2. 校验签名、过期时间
        // 3. 解析 userId, username → UserContext.setUserId() / setUsername()
        // 4. 失败则 response.sendError(401) 返回 false
        return true; // 骨架阶段放行
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // ⚠️ 关键：防止线程池复用导致 ThreadLocal 数据污染
        UserContext.clear();
    }
}
