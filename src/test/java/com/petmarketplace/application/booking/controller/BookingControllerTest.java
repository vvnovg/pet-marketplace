package com.petmarketplace.application.booking.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.petmarketplace.IntegrationTestBase;
import com.petmarketplace.application.admin.dto.ListingModerateRequest;
import com.petmarketplace.application.booking.dto.BookingResponse;
import com.petmarketplace.application.listing.dto.ListingCreateRequest;
import com.petmarketplace.application.listing.dto.ListingResponse;
import com.petmarketplace.domain.booking.entity.BookingStatus;
import com.petmarketplace.domain.listing.entity.ListingGender;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.user.entity.Role;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class BookingControllerTest extends IntegrationTestBase {

    private static final UUID DOGS_CATEGORY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LABRADOR_BREED_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void shouldBookConfirmAndCompleteListing() {
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser buyer = createUniqueUser(Role.BUYER);
        TestUser admin = createUniqueUser(Role.ADMIN);

        ListingResponse listing = createActiveListing(seller, admin);

        ResponseEntity<BookingResponse> bookResponse = restClient.post()
                .uri("/listings/" + listing.id() + "/book?message=Interested")
                .headers(authHeaders(buyer))
                .retrieve()
                .toEntity(BookingResponse.class);

        assertThat(bookResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        BookingResponse booking = bookResponse.getBody();
        assertThat(booking).isNotNull();
        assertThat(booking.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.buyer().id()).isEqualTo(buyer.id());
        assertThat(booking.seller().id()).isEqualTo(seller.id());
        assertThat(booking.listing().id()).isEqualTo(listing.id());

        ResponseEntity<BookingResponse> confirmResponse = restClient.put()
                .uri("/bookings/" + booking.id() + "/confirm")
                .headers(authHeaders(seller))
                .retrieve()
                .toEntity(BookingResponse.class);

        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmResponse.getBody()).isNotNull();
        assertThat(confirmResponse.getBody().status()).isEqualTo(BookingStatus.CONFIRMED);

        ResponseEntity<BookingResponse> completeResponse = restClient.put()
                .uri("/bookings/" + booking.id() + "/complete")
                .headers(authHeaders(seller))
                .retrieve()
                .toEntity(BookingResponse.class);

        assertThat(completeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completeResponse.getBody()).isNotNull();
        assertThat(completeResponse.getBody().status()).isEqualTo(BookingStatus.COMPLETED);
    }
}
