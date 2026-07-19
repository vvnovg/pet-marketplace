package com.petmarketplace.application.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @NotBlank
        @Size(min = 8)
        String password,

        @Size(max = 20)
        String phone,

        @Size(max = 100)
        String firstName,

        @Size(max = 100)
        String lastName
) {
}
