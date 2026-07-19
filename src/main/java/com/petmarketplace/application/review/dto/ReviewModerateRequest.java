package com.petmarketplace.application.review.dto;

import com.petmarketplace.domain.review.entity.ReviewStatus;
import jakarta.validation.constraints.NotNull;

public record ReviewModerateRequest(
        @NotNull ReviewStatus status,
        String reason
) {
}
