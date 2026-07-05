package com.gitnova.config;

import com.gitnova.interceptor.JwtInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册 JWT 拦截器
 *
 * 拦截路径：/api/**（排除 /api/auth/** 登录注册接口）
 */
@Configuration
public class JwtInterceptorConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    public JwtInterceptorConfig(JwtInterceptor jwtInterceptor) {
        this.jwtInterceptor = jwtInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**");
    }
}
