package com.petmarketplace.application.booking.dto;

import com.petmarketplace.domain.booking.entity.BookingStatus;
import jakarta.validation.constraints.NotNull;

public record BookingStatusUpdateRequest(
        @NotNull BookingStatus status
) {
}
