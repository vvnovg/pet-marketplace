package com.petmarketplace.application.auth.service;

import com.petmarketplace.exception.BusinessException;
import com.petmarketplace.infrastructure.security.JwtTokenProvider;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String KEY_PREFIX = "refresh:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;

    public String create(String email) {
        String token = jwtTokenProvider.generateRefreshToken(email);
        String key = KEY_PREFIX + token;
        try {
            redisTemplate.opsForValue().set(key, email, TTL);
        } catch (DataAccessException ex) {
            log.warn("Redis is unavailable, refresh token could not be stored: {}", ex.getMessage());
        }
        return token;
    }

    public Optional<String> findEmailByToken(String token) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(KEY_PREFIX + token));
        } catch (DataAccessException ex) {
            log.warn("Redis is unavailable, refresh token validation skipped: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public void invalidate(String token) {
        try {
            redisTemplate.delete(KEY_PREFIX + token);
        } catch (DataAccessException ex) {
            log.warn("Redis is unavailable, refresh token could not be invalidated: {}", ex.getMessage());
        }
    }

    public String rotate(String oldToken) {
        jwtTokenProvider.parseRefreshToken(oldToken);
        String email = findEmailByToken(oldToken)
                .orElseThrow(() -> new BusinessException("Invalid or expired refresh token"));
        invalidate(oldToken);
        return create(email);
    }
}
