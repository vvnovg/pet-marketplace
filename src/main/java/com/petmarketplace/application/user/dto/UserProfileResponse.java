package com.petmarketplace.application.user.dto;

import com.petmarketplace.domain.user.entity.Role;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String email,
        String phone,
        String firstName,
        String lastName,
        String avatarUrl,
        Role role,
        boolean verified,
        boolean active,
        String bio,
        String country,
        String city,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal rating,
        Integer totalReviews,
        Instant createdAt,
        Instant updatedAt
) {
}
