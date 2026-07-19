package com.petmarketplace.application.auth.service;

import com.petmarketplace.application.auth.dto.ForgotPasswordRequest;
import com.petmarketplace.application.auth.dto.LoginRequest;
import com.petmarketplace.application.auth.dto.PasswordResetRequest;
import com.petmarketplace.application.auth.dto.RefreshRequest;
import com.petmarketplace.application.auth.dto.RegisterRequest;
import com.petmarketplace.application.auth.dto.TokenResponse;
import com.petmarketplace.application.auth.mapper.AuthMapper;
import com.petmarketplace.application.user.service.ProfileService;
import com.petmarketplace.domain.user.entity.Role;
import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.domain.user.repository.UserRepository;
import com.petmarketplace.exception.BusinessException;
import com.petmarketplace.infrastructure.notification.EmailNotificationService;
import com.petmarketplace.infrastructure.security.JwtProperties;
import com.petmarketplace.infrastructure.security.JwtTokenProvider;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String VERIFY_PREFIX = "verify:";
    private static final String RESET_PREFIX = "reset:";

    private static final Duration VERIFY_TTL = Duration.ofHours(24);
    private static final Duration RESET_TTL = Duration.ofHours(1);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenService refreshTokenService;
    private final EmailNotificationService emailNotificationService;
    private final StringRedisTemplate redisTemplate;
    private final AuthMapper authMapper;
    private final ProfileService profileService;

    @Transactional
    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException("Email already registered");
        }

        User user = authMapper.toUser(request);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.BUYER);
        user.setVerified(false);
        user.setActive(true);
        userRepository.save(user);
        profileService.createEmptyProfile(user);

        String token = UUID.randomUUID().toString();
        storeToken(VERIFY_PREFIX + token, request.email(), VERIFY_TTL);
        emailNotificationService.sendVerificationEmail(user, token);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException("Invalid credentials"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException("Invalid credentials");
        }
        if (!user.isVerified()) {
            throw new BusinessException("Email not verified");
        }
        if (!user.isActive()) {
            throw new BusinessException("Account is disabled");
        }

        String accessToken = jwtTokenProvider.generateAccessToken(user.getEmail(), user.getRole());
        String refreshToken = refreshTokenService.create(user.getEmail());

        return new TokenResponse(accessToken, refreshToken, "Bearer", jwtProperties.getAccessExpirationMinutes() * 60);
    }

    @Transactional(readOnly = true)
    public TokenResponse refresh(RefreshRequest request) {
        String email = refreshTokenService.findEmailByToken(request.refreshToken())
                .orElseThrow(() -> new BusinessException("Invalid or expired refresh token"));

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("User not found"));
        if (!user.isActive()) {
            throw new BusinessException("Account is disabled");
        }

        String accessToken = jwtTokenProvider.generateAccessToken(user.getEmail(), user.getRole());
        String refreshToken = refreshTokenService.rotate(request.refreshToken());

        return new TokenResponse(accessToken, refreshToken, "Bearer", jwtProperties.getAccessExpirationMinutes() * 60);
    }

    public void logout(RefreshRequest request) {
        refreshTokenService.invalidate(request.refreshToken());
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        Optional<User> userOpt = userRepository.findByEmail(request.email());
        if (userOpt.isEmpty()) {
            log.debug("Password reset requested for unknown email: {}", request.email());
            return;
        }

        User user = userOpt.get();
        String token = UUID.randomUUID().toString();
        storeToken(RESET_PREFIX + token, user.getEmail(), RESET_TTL);
        emailNotificationService.sendPasswordResetEmail(user, token);
    }

    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        String email = fetchToken(RESET_PREFIX + request.token())
                .orElseThrow(() -> new BusinessException("Invalid or expired reset token"));

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("User not found"));
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        deleteToken(RESET_PREFIX + request.token());
    }

    @Transactional
    public void verifyEmail(String token) {
        String email = fetchToken(VERIFY_PREFIX + token)
                .orElseThrow(() -> new BusinessException("Invalid or expired verification token"));

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("User not found"));
        user.setVerified(true);
        userRepository.save(user);

        deleteToken(VERIFY_PREFIX + token);
    }

    private void storeToken(String key, String email, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, email, ttl);
        } catch (DataAccessException ex) {
            log.warn("Redis is unavailable, token could not be stored: {}", ex.getMessage());
        }
    }

    private Optional<String> fetchToken(String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key));
        } catch (DataAccessException ex) {
            log.warn("Redis is unavailable, token could not be retrieved: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private void deleteToken(String key) {
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException ex) {
            log.warn("Redis is unavailable, token could not be deleted: {}", ex.getMessage());
        }
    }
}
