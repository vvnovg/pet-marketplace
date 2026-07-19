package com.petmarketplace.application.user.dto;

import com.petmarketplace.domain.user.entity.Role;
import java.math.BigDecimal;
import java.util.UUID;

public record PublicProfileResponse(
        UUID id,
        String firstName,
        String lastName,
        String avatarUrl,
        String bio,
        String country,
        String city,
        BigDecimal rating,
        Integer totalReviews,
        Role role
) {
}
