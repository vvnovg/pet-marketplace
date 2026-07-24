package com.petmarketplace.application.listing.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.petmarketplace.IntegrationTestBase;
import com.petmarketplace.application.admin.dto.ListingModerateRequest;
import com.petmarketplace.application.listing.dto.ListingCreateRequest;
import com.petmarketplace.application.listing.dto.ListingResponse;
import com.petmarketplace.domain.listing.entity.ListingGender;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.user.entity.Role;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

class ListingControllerTest extends IntegrationTestBase {

    private static final UUID DOGS_CATEGORY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LABRADOR_BREED_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void shouldCreateListingAsSellerAndModerateAsAdmin() {
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser admin = createUniqueUser(Role.ADMIN);

        ListingCreateRequest createRequest = new ListingCreateRequest(
                DOGS_CATEGORY_ID,
                LABRADOR_BREED_ID,
                "Healthy Labrador puppy",
                "Friendly and vaccinated puppy.",
                BigDecimal.valueOf(50000),
                "RUB",
                ListingGender.MALE,
                4,
                "Yellow",
                BigDecimal.valueOf(12.5),
                "Vaccinated",
                true,
                true,
                "Russia",
                "Moscow"
        );

        ResponseEntity<ListingResponse> createResponse = restClient.post()
                .uri("/listings")
                .body(createRequest)
                .headers(authHeaders(seller))
                .retrieve()
                .toEntity(ListingResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ListingResponse created = createResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.status()).isEqualTo(ListingStatus.PENDING_MODERATION);
        assertThat(created.seller().id()).isEqualTo(seller.id());

        ListingModerateRequest moderateRequest = new ListingModerateRequest(ListingStatus.ACTIVE, "Looks good");
        ResponseEntity<ListingResponse> moderateResponse = restClient.put()
                .uri("/admin/listings/" + created.id() + "/moderate")
                .body(moderateRequest)
                .headers(authHeaders(admin))
                .retrieve()
                .toEntity(ListingResponse.class);

        assertThat(moderateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(moderateResponse.getBody()).isNotNull();
        assertThat(moderateResponse.getBody().status()).isEqualTo(ListingStatus.ACTIVE);

        ResponseEntity<ListingResponse> getResponse = restClient.get()
                .uri("/listings/" + created.id())
                .retrieve()
                .toEntity(ListingResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().id()).isEqualTo(created.id());

        String searchUrl = UriComponentsBuilder.fromPath("/listings")
                .queryParam("categoryId", DOGS_CATEGORY_ID)
                .queryParam("city", "Moscow")
                .toUriString();

        // Read the page as a raw String and parse it ourselves: deserializing straight into
        // JsonNode via the default RestClient converter fails in this Jackson 2/3 mixed setup.
        ResponseEntity<String> searchResponse = getStatus(searchUrl, null);

        assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(searchResponse.getBody()).isNotNull();
        JsonNode searchBody = parse(searchResponse);
        assertThat(searchBody.get("content").isArray()).isTrue();
        boolean found = false;
        for (JsonNode node : searchBody.get("content")) {
            if (created.id().toString().equals(node.get("id").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void shouldFindListingByPartialCaseInsensitiveCityMatch() {
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser admin = createUniqueUser(Role.ADMIN);

        ListingCreateRequest createRequest = new ListingCreateRequest(
                DOGS_CATEGORY_ID,
                LABRADOR_BREED_ID,
                "Healthy Labrador puppy",
                "Friendly and vaccinated puppy.",
                BigDecimal.valueOf(50000),
                "RUB",
                ListingGender.MALE,
                4,
                "Yellow",
                BigDecimal.valueOf(12.5),
                "Vaccinated",
                true,
                true,
                "Russia",
                "Moscow"
        );

        ResponseEntity<ListingResponse> createResponse = restClient.post()
                .uri("/listings")
                .body(createRequest)
                .headers(authHeaders(seller))
                .retrieve()
                .toEntity(ListingResponse.class);
        ListingResponse created = createResponse.getBody();
        assertThat(created).isNotNull();

        ListingModerateRequest moderateRequest = new ListingModerateRequest(ListingStatus.ACTIVE, "Looks good");
        restClient.put()
                .uri("/admin/listings/" + created.id() + "/moderate")
                .body(moderateRequest)
                .headers(authHeaders(admin))
                .retrieve()
                .toEntity(ListingResponse.class);

        // User types a lowercase substring of the city ("mosc"), not the exact full name.
        String searchUrl = UriComponentsBuilder.fromPath("/listings")
                .queryParam("city", "mosc")
                .toUriString();

        ResponseEntity<String> searchResponse = getStatus(searchUrl, null);

        assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode searchBody = parse(searchResponse);
        boolean found = false;
        for (JsonNode node : searchBody.get("content")) {
            if (created.id().toString().equals(node.get("id").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    private JsonNode parse(ResponseEntity<String> res) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
