package com.gitnova.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
/**
 * JWT 工具类 — 生成 Token / 解析 Token
 *
 * @author zhaoguodong
 */
@Component
public class JwtUtil {
    private final SecretKey key;
    private final long expire;
    public JwtUtil(
        @Value ("${gitnova.jwt.secret}")
        String secret,
        @Value ("${gitnova.jwt.expire}")
        long expire
    ) {
        this.key=Keys.hmacShaKeyFor(
                Decoders.BASE64.decode(secret));
        this.expire = expire;
    }

        public String generateToken(Long userId){
        if(userId==null||userId<=0){
            throw new IllegalArgumentException("Invalid userId");
        }
        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis()+ expire))
                .signWith(key)
                .compact();
        }
    
        public Claims parseToken(String token){
                return Jwts.parser()
                        .verifyWith(key)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
        }
        
        public Long verifyAndGetUserId(String token) {
                Claims claims = parseToken(token);

                if (claims.getExpiration() == null) {
                    throw new IllegalArgumentException("Token missing expiration");
            }
                String subject = claims.getSubject();
                if (subject == null || subject.isBlank()) {
                    throw new IllegalArgumentException("Token missing subject");
            }
                long userId = Long.parseLong(subject);
                if (userId <= 0) {
                throw new IllegalArgumentException("Invalid userId");
            }
                return userId;
            }
        }
