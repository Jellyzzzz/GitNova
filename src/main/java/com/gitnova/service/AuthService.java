package com.gitnova.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gitnova.dto.ApiResponse;
import com.gitnova.entity.User;
import com.gitnova.mapper.UserMapper;
import com.gitnova.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 认证服务 — 注册 / 登录
 */
@Service
public class AuthService {
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtUtil jwtUtil;

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
        if(username==null||username.isEmpty()) return ApiResponse.error(400,"用户名不能为空");
        if(username.length()<3||username.length()>50) return ApiResponse.error(400,"用户名长度应在 3~50 之间");
        for(char c:username.toCharArray()){
            if(!((c>='0'&&c<='9')||(c>='a'&&c<='z')||(c>='A'&&c<='Z')||c=='_')) return ApiResponse.error(400,"用户名仅允许字母、数字、下划线");
        }

        if(password==null||password.isEmpty()) return ApiResponse.error(400,"密码不能为空");
        if(password.length()<6||password.length()>100) return ApiResponse.error(400,"密码长度应在 6~100 之间");

        if(email!=null&&!email.contains("@")) return ApiResponse.error(400, "邮箱格式错误");

        LambdaQueryWrapper<User>wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername,username);
        User user=userMapper.selectOne(wrapper);
        if(user!=null) return ApiResponse.error(400,"用户名已存在");

        String encodePassword=passwordEncoder.encode(password);

        User newUser=new User();
        newUser.setUsername(username);
        newUser.setEmail(email);
        newUser.setPassword(encodePassword);
        newUser.setCreatedAt(LocalDateTime.now());
        userMapper.insert(newUser);
        return ApiResponse.success(Map.of("id", newUser.getId(), "username", newUser.getUsername()));
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
        if(username==null) return ApiResponse.error(400,"用户名不能为空");
        if(password==null) return ApiResponse.error(400,"密码不能为空");

        LambdaQueryWrapper<User>wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername,username);
        User user=userMapper.selectOne(wrapper);
        if(user==null) return ApiResponse.error(401,"用户名或密码错误");

        if(!passwordEncoder.matches(password,user.getPassword())) return ApiResponse.error(401,"用户名或密码错误");

        String token=jwtUtil.generateToken(user.getId(),user.getUsername());
        return ApiResponse.success(token);
    }
}
