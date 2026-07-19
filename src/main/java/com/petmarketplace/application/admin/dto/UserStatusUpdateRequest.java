package com.petmarketplace.application.admin.dto;

import jakarta.validation.constraints.NotNull;

public record UserStatusUpdateRequest(
        @NotNull Boolean active,
        String reason
) {
}
