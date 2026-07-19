package com.petmarketplace.infrastructure.kafka;

import java.io.Serializable;
import java.util.UUID;

/**
 * Inbound Kafka request payload: ask for animal info by listing id. The {@code correlationId}
 * travels in a Kafka header (see {@code AnimalInfoRequestListener}), not in this body.
 */
public record AnimalInfoRequest(UUID listingId) implements Serializable {

    private static final long serialVersionUID = 1L;
}