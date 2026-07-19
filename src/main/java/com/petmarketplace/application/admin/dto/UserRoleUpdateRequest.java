package com.petmarketplace.application.admin.dto;

import com.petmarketplace.domain.user.entity.Role;
import jakarta.validation.constraints.NotNull;

public record UserRoleUpdateRequest(
        @NotNull Role role
) {
}
