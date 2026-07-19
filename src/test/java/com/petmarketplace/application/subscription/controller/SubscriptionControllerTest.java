package com.petmarketplace.application.subscription.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.petmarketplace.IntegrationTestBase;
import com.petmarketplace.application.subscription.dto.SubscriptionCreateRequest;
import com.petmarketplace.domain.listing.entity.ListingGender;
import com.petmarketplace.domain.user.entity.Role;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Covers module §10 (subscriptions) of AUTOTESTS_SPECIFICATION.md. */
class SubscriptionControllerTest extends IntegrationTestBase {

    @Test
    void emptySubscriptionsForNewUser() {
        TestUser buyer = createUniqueUser(Role.BUYER);
        assertThat(parseArray(getStatus("/subscriptions", buyer)).size()).isZero();
    }

    @Test
    void createSubscription() {
        TestUser buyer = createUniqueUser(Role.BUYER);
        SubscriptionCreateRequest req = new SubscriptionCreateRequest(
                DOGS_CATEGORY_ID, null, "Moscow", BigDecimal.ZERO, null,
                ListingGender.MALE, null, null, true, null);

        ResponseEntity<String> res = postStatus("/subscriptions", req, buyer);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = parse(res);
        assertThat(body.get("id").asText()).isNotBlank();
        assertThat(body.get("active").asText()).isEqualTo("true");

        assertThat(parseArray(getStatus("/subscriptions", buyer)).size()).isEqualTo(1);
    }

    @Test
    void createSubscriptionEmptyBodyAllowed() {
        TestUser buyer = createUniqueUser(Role.BUYER);
        SubscriptionCreateRequest req = new SubscriptionCreateRequest(
                null, null, null, null, null, null, null, null, null, null);
        assertThat(postStatus("/subscriptions", req, buyer).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void negativePriceRejected() {
        TestUser buyer = createUniqueUser(Role.BUYER);
        SubscriptionCreateRequest req = new SubscriptionCreateRequest(
                null, null, null, new BigDecimal("-1"), null, null, null, null, null, null);
        assertThat(postStatus("/subscriptions", req, buyer).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deleteOwnSubscription() {
        TestUser buyer = createUniqueUser(Role.BUYER);
        ResponseEntity<String> created = postStatus("/subscriptions",
                new SubscriptionCreateRequest(null, null, null, null, null, null,
                        null, null, null, null), buyer);
        UUID id = UUID.fromString(parse(created).get("id").asText());

        assertThat(deleteStatus("/subscriptions/" + id, buyer).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(parseArray(getStatus("/subscriptions", buyer)).size()).isZero();
    }

    @Test
    void deleteForeignSubscriptionForbidden() {
        TestUser owner = createUniqueUser(Role.BUYER);
        TestUser other = createUniqueUser(Role.BUYER);
        ResponseEntity<String> created = postStatus("/subscriptions",
                new SubscriptionCreateRequest(null, null, null, null, null, null,
                        null, null, null, null), owner);
        UUID id = UUID.fromString(parse(created).get("id").asText());

        // Service-level ownership check -> BusinessException 409.
        assertThat(deleteStatus("/subscriptions/" + id, other).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void deleteUnknownSubscriptionNotFound() {
        TestUser buyer = createUniqueUser(Role.BUYER);
        assertThat(deleteStatus("/subscriptions/" + UUID.randomUUID(), buyer).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void subscriptionsRequireAuth() {
        assertThat(getStatus("/subscriptions", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(postStatus("/subscriptions",
                new SubscriptionCreateRequest(null, null, null, null, null, null,
                        null, null, null, null), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private JsonNode parse(ResponseEntity<String> res) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode parseArray(ResponseEntity<String> res) {
        return parse(res);
    }
}