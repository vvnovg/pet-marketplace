package com.petmarketplace.application.category.dto;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

public record CategoryWithBreedsResponse(
        UUID id,
        String name,
        String slug,
        List<BreedResponse> breeds
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public CategoryWithBreedsResponse {
        if (breeds == null) {
            breeds = List.of();
        }
    }
}
