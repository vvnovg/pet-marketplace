package com.petmarketplace.infrastructure.security;

import com.petmarketplace.domain.user.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;
    private final SecretKey key;

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    public JwtTokenProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(String email, Role role) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtProperties.getAccessExpirationMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(email)
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                // Unique JWT id so two access tokens issued within the same second (identical
                // iat/exp/claims) still differ — needed for deterministic rotation checks.
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public String generateRefreshToken(String email) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtProperties.getRefreshExpirationDays(), ChronoUnit.DAYS);
        return Jwts.builder()
                .subject(email)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public Claims parseAccessToken(String token) {
        Claims claims = parseToken(token);
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("Token is not an access token");
        }
        return claims;
    }

    public Claims parseRefreshToken(String token) {
        Claims claims = parseToken(token);
        if (!TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("Token is not a refresh token");
        }
        return claims;
    }

    public String getEmailFromToken(String token) {
        return parseToken(token).getSubject();
    }

    public Role getRoleFromToken(String token) {
        String roleName = parseToken(token).get(CLAIM_ROLE, String.class);
        try {
            return Role.valueOf(roleName);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new JwtException("Invalid role claim in token", ex);
        }
    }

    private Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (io.jsonwebtoken.ExpiredJwtException ex) {
            throw new JwtException("JWT token has expired", ex);
        } catch (io.jsonwebtoken.UnsupportedJwtException ex) {
            throw new JwtException("Unsupported JWT token", ex);
        } catch (io.jsonwebtoken.MalformedJwtException ex) {
            throw new JwtException("Malformed JWT token", ex);
        } catch (io.jsonwebtoken.security.SecurityException ex) {
            throw new JwtException("Invalid JWT signature", ex);
        } catch (IllegalArgumentException ex) {
            throw new JwtException("JWT token is empty or invalid", ex);
        }
    }
}
