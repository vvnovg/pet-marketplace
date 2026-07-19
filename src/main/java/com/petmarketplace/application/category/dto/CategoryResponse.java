package com.petmarketplace.application.category.dto;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

public record CategoryResponse(
        UUID id,
        String name,
        String slug,
        List<CategoryResponse> children,
        List<BreedResponse> breeds
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public CategoryResponse {
        if (children == null) {
            children = List.of();
        }
        if (breeds == null) {
            breeds = List.of();
        }
    }
}
