package com.gitnova.service;

import com.gitnova.dto.ApiResponse;
import com.gitnova.mapper.UserMapper;
import org.springframework.stereotype.Service;

/**
 * 认证服务 — 注册 / 登录
 */
@Service
public class AuthService {

    private final UserMapper userMapper;

    public AuthService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 用户注册
     *
     * @param username 用户名
     * @param password 明文密码（服务端 BCrypt 加密后存储）
     * @param email    邮箱
     * @return ApiResponse
     */
    public ApiResponse<?> register(String username, String password, String email) {
        // TODO: Phase 1 — 注册逻辑
        // 1. 校验 username 唯一性
        // 2. BCrypt 加密密码
        // 3. 写入 user 表
        // 4. 返回成功
        throw new UnsupportedOperationException("Phase 1: 待实现");
    }

    /**
     * 用户登录
     *
     * @param username 用户名
     * @param password 明文密码
     * @return JWT token
     */
    public ApiResponse<String> login(String username, String password) {
        // TODO: Phase 1 — 登录逻辑
        // 1. 查 user 表校验用户名密码
        // 2. 生成 JWT（userId + username + expire）
        // 3. 返回 token
        throw new UnsupportedOperationException("Phase 1: 待实现");
    }
}
