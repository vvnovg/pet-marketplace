package com.petmarketplace.application.user.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.petmarketplace.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;

/** Covers module §3 (users) of AUTOTESTS_SPECIFICATION.md. */
class UserControllerTest extends IntegrationTestBase {

    @Test
    void getCurrentProfileRequiresAuth() {
        assertThat(getStatus("/users/me", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getCurrentProfileReturnsOwnData() {
        var buyer = createUniqueUser(com.petmarketplace.domain.user.entity.Role.BUYER);
        ResponseEntity<String> res = getStatus("/users/me", buyer);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = parse(res);
        assertThat(body.get("email").asText()).isEqualTo(buyer.email());
        assertThat(body.has("password")).isFalse();
        assertThat(body.has("passwordHash")).isFalse();
    }

    @Test
    void updateProfilePersistsFields() {
        var buyer = createUniqueUser(com.petmarketplace.domain.user.entity.Role.BUYER);
        ResponseEntity<String> res = putStatus("/users/me",
                new com.petmarketplace.application.user.dto.ProfileUpdateRequest(
                        "new bio", "Russia", "Samara", "street", null, null), buyer);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(res).get("bio").asText()).isEqualTo("new bio");
        assertThat(parse(res).get("city").asText()).isEqualTo("Samara");
    }

    @Test
    void updateProfileValidationRejectsOutOfRangeLatitude() {
        var buyer = createUniqueUser(com.petmarketplace.domain.user.entity.Role.BUYER);
        assertThat(putStatus("/users/me",
                new com.petmarketplace.application.user.dto.ProfileUpdateRequest(
                        null, null, null, null, new BigDecimal("-91"), null), buyer)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(putStatus("/users/me",
                new com.petmarketplace.application.user.dto.ProfileUpdateRequest(
                        null, null, null, null, null, new BigDecimal("181")), buyer)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateProfileValidationRejectsTooLongBio() {
        var buyer = createUniqueUser(com.petmarketplace.domain.user.entity.Role.BUYER);
        assertThat(putStatus("/users/me",
                new com.petmarketplace.application.user.dto.ProfileUpdateRequest(
                        "x".repeat(2001), null, null, null, null, null), buyer)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void publicProfileAccessibleWithoutToken() {
        var seller = createUniqueUser(com.petmarketplace.domain.user.entity.Role.SELLER);
        ResponseEntity<String> res = getStatus("/users/" + seller.id(), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(res).get("id").asText()).isEqualTo(seller.id().toString());
    }

    @Test
    void publicProfileUnknownUserNotFound() {
        assertThat(getStatus("/users/" + UUID.randomUUID(), null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void userListingsStubReturnsEmpty() {
        // UserService.listUserListings is a TODO stub returning Page.empty().
        var seller = createUniqueUser(com.petmarketplace.domain.user.entity.Role.SELLER);
        ResponseEntity<String> res = getStatus("/users/" + seller.id() + "/listings", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(res).get("content").size()).isZero();
    }

    @Test
    void uploadAvatarRequiresImage() {
        var buyer = createUniqueUser(com.petmarketplace.domain.user.entity.Role.BUYER);
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", "not-an-image".getBytes(), MediaType.TEXT_PLAIN)
                .filename("file.txt");

        ResponseEntity<String> res = restClient.post()
                .uri("/users/me/avatar")
                .body(builder.build())
                .headers(authHeaders(buyer))
                .retrieve()
                .onStatus(s -> true, (req, r) -> { })
                .toEntity(String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void uploadAvatarRequiresAuth() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new byte[]{1}, MediaType.IMAGE_PNG).filename("a.png");
        ResponseEntity<String> res = restClient.post()
                .uri("/users/me/avatar")
                .body(builder.build())
                .retrieve()
                .onStatus(s -> true, (req, r) -> { })
                .toEntity(String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private JsonNode parse(ResponseEntity<String> res) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}