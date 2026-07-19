package com.petmarketplace.application.subscription.dto;

import com.petmarketplace.domain.subscription.entity.SubscriptionFilters;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record SubscriptionResponse(
        UUID id,
        SubscriptionFilters filters,
        boolean active,
        Instant createdAt
) implements Serializable {

    private static final long serialVersionUID = 1L;
}
