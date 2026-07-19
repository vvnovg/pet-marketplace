package com.petmarketplace.application.category.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.petmarketplace.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Covers module §4 (categories) of AUTOTESTS_SPECIFICATION.md. */
class CategoryControllerTest extends IntegrationTestBase {

    @Test
    void listCategoriesPublicAndContainsDogs() {
        ResponseEntity<String> res = getStatus("/categories", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = parse(res);
        assertThat(body.isArray()).isTrue();
        assertThat(body.findValuesAsText("id")).contains(DOGS_CATEGORY_ID.toString());
    }

    @Test
    void listCategoriesLocalized() {
        ResponseEntity<String> ru = restClient.get().uri("/categories")
                .header("Accept-Language", "ru").retrieve().toEntity(String.class);
        ResponseEntity<String> en = restClient.get().uri("/categories")
                .header("Accept-Language", "en").retrieve().toEntity(String.class);
        assertThat(ru.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(en.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Both localizations must return content (names differ by locale).
        assertThat(parse(ru).size()).isPositive();
        assertThat(parse(en).size()).isPositive();
    }

    @Test
    void breedsByCategoryReturnsLabrador() {
        ResponseEntity<String> res = getStatus("/categories/" + DOGS_CATEGORY_ID + "/breeds", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(res).get("breeds").findValuesAsText("id"))
                .contains(LABRADOR_BREED_ID.toString());
    }

    @Test
    void breedsByUnknownCategoryNotFound() {
        assertThat(getStatus("/categories/" + UUID.randomUUID() + "/breeds", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private JsonNode parse(ResponseEntity<String> res) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}