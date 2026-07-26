package com.petmarketplace.application.user.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ProfileUpdateRequest(
        @Size(max = 2000)
        String bio,

        @Size(max = 100)
        String country,

        @Size(max = 100)
        String city,

        @Size(max = 255)
        String address,

        @DecimalMin(value = "-90.0")
        @DecimalMax(value = "90.0")
        BigDecimal latitude,

        @DecimalMin(value = "-180.0")
        @DecimalMax(value = "180.0")
        BigDecimal longitude,

        @Size(max = 100)
        String firstName,

        @Size(max = 100)
        String lastName,

        @Size(max = 20)
        String phone
) {
}
