package com.petmarketplace.application.imports.dto;

import jakarta.validation.constraints.NotBlank;

public record AnimalImportRequest(
        @NotBlank String bucket,
        @NotBlank String objectKey) {
}
