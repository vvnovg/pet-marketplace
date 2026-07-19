package com.petmarketplace.infrastructure.kafka;

/**
 * Outcome discriminator carried in {@link AnimalInfoResponse#status()}. Jackson 3 serializes the
 * enum by its name ("OK" / "NOT_FOUND" / "ERROR").
 */
public enum ReplyStatus {
    OK,
    NOT_FOUND,
    ERROR
}