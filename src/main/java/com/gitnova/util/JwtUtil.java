package com.gitnova.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
/**
 * JWT 工具类 — 生成 Token / 解析 Token
 *
 * @author TODO
 */
@Component
public class JwtUtil {

    // TODO: Phase 1 — 构造函数（接收 secret + expireMs，构建 SecretKey）
    @Value("${gitnova.jwt.secret}")
    private  String secret;
    @Value("${gitnova.jwt.expire}")
    private long expire;


    private  SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
    // TODO: Phase 1 — generateToken(Long userId, String username) → String
        public  String generateToken(Long userId,String username){
        return Jwts.builder()
                .subject(username)
                .claim("userId",userId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis()+ expire))
                .signWith(getSecretKey())
                .compact();
        }
    // TODO: Phase 1 — parseToken(String token) → Claims
        public  Claims parseToken(String token){
                return Jwts.parser()
                        .verifyWith(getSecretKey())
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
        }
    // TODO: Phase 1 — getUserId(String token) → Long

    // TODO: Phase 1 — getUsername(String token) → String

    // TODO: Phase 1 — validateToken(String token) → boolean
}
