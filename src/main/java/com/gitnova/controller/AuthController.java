package com.gitnova.controller;

import com.gitnova.dto.ApiResponse;
import com.gitnova.service.AuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口 — 注册 / 登录
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public ApiResponse<?> register(@RequestParam String username,
                                   @RequestParam String password,
                                   @RequestParam(required = false) String email) {
        return authService.register(username, password, email);
    }

    /**
     * 用户登录 — 返回 JWT token
     */
    @PostMapping("/login")
    public ApiResponse<String> login(@RequestParam String username,
                                     @RequestParam String password) {
        return authService.login(username, password);
    }
}
