package com.gitnova.config;

import com.gitnova.interceptor.JwtInterceptor;
import com.gitnova.ratelimit.RateLimitInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册 JWT 拦截器
 *
 * 拦截路径：/api/**（排除 /api/auth/** 登录注册接口）
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class JwtInterceptorConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    public JwtInterceptorConfig(
            JwtInterceptor jwtInterceptor,
            RateLimitInterceptor rateLimitInterceptor
    ) {
        this.jwtInterceptor = jwtInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**")
                .order(0);
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .order(1);
    }
}
