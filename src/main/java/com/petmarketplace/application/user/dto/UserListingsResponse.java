package com.petmarketplace.application.user.dto;

import java.util.List;

/**
 * Placeholder response for a user's listings.
 * The concrete listing DTO will be introduced by the listings module.
 */
public record UserListingsResponse(List<Object> listings) {
}
