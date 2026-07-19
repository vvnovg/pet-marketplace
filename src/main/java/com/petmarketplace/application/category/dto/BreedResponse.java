package com.petmarketplace.application.category.dto;

import java.io.Serializable;
import java.util.UUID;

public record BreedResponse(UUID id, String name) implements Serializable {

    private static final long serialVersionUID = 1L;
}
