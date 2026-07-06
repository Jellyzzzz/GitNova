package com.gitnova.interceptor;

import com.gitnova.common.UserContext;
import com.gitnova.entity.User;
import com.gitnova.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    JwtUtil jwtUtil;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // TODO: Phase 1 — JWT 校验逻辑
        // 1. 从 Header 取 Authorization: Bearer <token>
        // 2. 校验签名、过期时间
        // 3. 解析 userId, username → UserContext.setUserId() / setUsername()
        // 4. 失败则 response.sendError(401) 返回 false
        String header=request.getHeader("Authorization");
        if(header!=null&&header.startsWith("Bearer ")){
            String token=header.substring(7);

            try{
                Claims claims= jwtUtil.parseToken(token);
                String username=claims.get("username", String.class);
                Long userId=claims.get("userId",Long.class);

                UserContext.setUsername(username);
                UserContext.setUserId(userId);
                return true;
            }catch(io.jsonwebtoken.JwtException | IllegalArgumentException e){
                response.setContentType("application/json;charset=UTF-8");
                response.setStatus(401);
                response.getWriter().write("{\"code\":401,\"message\":\"" + e.getMessage() + "\"}");
                return false;
            }
        }
        return false; // 拦截请求，不再进入 Controller
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // ⚠️ 关键：防止线程池复用导致 ThreadLocal 数据污染
        UserContext.clear();
    }
}
