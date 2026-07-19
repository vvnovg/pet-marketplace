package com.petmarketplace.application.category.controller;

import com.petmarketplace.application.category.dto.CategoryResponse;
import com.petmarketplace.application.category.dto.CategoryWithBreedsResponse;
import com.petmarketplace.application.category.service.CategoryService;
import com.petmarketplace.infrastructure.localization.LocalizedNameResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
@Tag(name = "Categories", description = "Animal categories and breeds")
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "List all root categories")
    @ApiResponse(responseCode = "200", description = "Categories retrieved")
    @GetMapping
    public List<CategoryResponse> getCategories(
            @Parameter(description = "Preferred language for names: ru or en")
            @RequestHeader(name = "Accept-Language", required = false, defaultValue = "ru") String language) {
        Locale locale = LocalizedNameResolver.resolveLocale(language);
        return categoryService.getAllRootCategories(locale);
    }

    @Operation(summary = "Get breeds for a category")
    @ApiResponse(responseCode = "200", description = "Breeds retrieved")
    @ApiResponse(responseCode = "404", description = "Category not found")
    @GetMapping("/{id}/breeds")
    public CategoryWithBreedsResponse getBreedsByCategoryId(
            @PathVariable UUID id,
            @Parameter(description = "Preferred language for names: ru or en")
            @RequestHeader(name = "Accept-Language", required = false, defaultValue = "ru") String language) {
        Locale locale = LocalizedNameResolver.resolveLocale(language);
        return categoryService.getBreedsByCategoryId(id, locale);
    }
}
