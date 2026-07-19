package com.petmarketplace.application.favorite.controller;

import com.petmarketplace.application.favorite.dto.FavoriteResponse;
import com.petmarketplace.application.favorite.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/favorites")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Favorites", description = "Saved listings")
@SecurityRequirement(name = "bearer-jwt")
public class FavoriteController {

    private final FavoriteService favoriteService;

    @Operation(summary = "List current user favorites")
    @ApiResponse(responseCode = "200", description = "Favorites retrieved")
    @GetMapping
    public List<FavoriteResponse> list() {
        return favoriteService.getFavorites();
    }

    @Operation(summary = "Add a listing to favorites")
    @ApiResponse(responseCode = "201", description = "Added to favorites")
    @ApiResponse(responseCode = "404", description = "Listing not found")
    @PostMapping("/{listingId}")
    @ResponseStatus(HttpStatus.CREATED)
    public void add(@PathVariable UUID listingId) {
        favoriteService.addToFavorites(listingId);
    }

    @Operation(summary = "Remove a listing from favorites")
    @ApiResponse(responseCode = "204", description = "Removed from favorites")
    @ApiResponse(responseCode = "404", description = "Favorite not found")
    @DeleteMapping("/{listingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID listingId) {
        favoriteService.removeFromFavorites(listingId);
    }
}
