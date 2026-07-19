package com.petmarketplace.application.favorite.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.petmarketplace.IntegrationTestBase;
import com.petmarketplace.domain.user.entity.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Covers module §9 (favorites) of AUTOTESTS_SPECIFICATION.md. */
class FavoriteControllerTest extends IntegrationTestBase {

    @Test
    void emptyFavoritesForNewUser() {
        TestUser buyer = createUniqueUser(Role.BUYER);
        ResponseEntity<String> res = getStatus("/favorites", buyer);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parseArray(res).size()).isZero();
    }

    @Test
    void addFavoriteIsIdempotent() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser buyer = createUniqueUser(Role.BUYER);
        TestUser seller = createUniqueUser(Role.SELLER);
        UUID listingId = createActiveListing(seller, admin).id();

        ResponseEntity<String> first = postStatus("/favorites/" + listingId, null, buyer);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Second add must not duplicate.
        ResponseEntity<String> second = postStatus("/favorites/" + listingId, null, buyer);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> list = getStatus("/favorites", buyer);
        assertThat(parseArray(list).size()).isEqualTo(1);
    }

    @Test
    void removeFavorite() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser buyer = createUniqueUser(Role.BUYER);
        TestUser seller = createUniqueUser(Role.SELLER);
        UUID listingId = createActiveListing(seller, admin).id();

        postStatus("/favorites/" + listingId, null, buyer);
        assertThat(deleteStatus("/favorites/" + listingId, buyer).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(parseArray(getStatus("/favorites", buyer)).size()).isZero();
    }

    @Test
    void removeNonFavoriteIsIdempotentNoContent() {
        TestUser buyer = createUniqueUser(Role.BUYER);
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        UUID listingId = createActiveListing(seller, admin).id();
        // Listing exists but user never favorited it: DELETE is idempotent and returns 204
        // (the app does not treat "was not a favorite" as a 404 — only an unknown listing is 404).
        assertThat(deleteStatus("/favorites/" + listingId, buyer).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void addFavoriteUnknownListingNotFound() {
        TestUser buyer = createUniqueUser(Role.BUYER);
        assertThat(postStatus("/favorites/" + UUID.randomUUID(), null, buyer).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void favoritesRequireAuth() {
        assertThat(getStatus("/favorites", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(postStatus("/favorites/" + UUID.randomUUID(), null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private JsonNode parseArray(ResponseEntity<String> res) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}