package com.petmarketplace.application.listing.dto;

import java.io.Serializable;
import java.util.UUID;

public record ListingImageResponse(
        UUID id,
        String url,
        Integer orderIndex,
        Boolean isMain
) implements Serializable {

    private static final long serialVersionUID = 1L;
}
