package com.petmarketplace.application.booking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record BookingCreateRequest(
        @NotNull UUID listingId,
        @Size(max = 2000) String message
) {
}
