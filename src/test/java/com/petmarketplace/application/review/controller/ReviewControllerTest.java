package com.petmarketplace.application.review.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.petmarketplace.IntegrationTestBase;
import com.petmarketplace.application.booking.dto.BookingResponse;
import com.petmarketplace.application.listing.dto.ListingResponse;
import com.petmarketplace.application.review.dto.ReviewCreateRequest;
import com.petmarketplace.domain.review.entity.ReviewStatus;
import com.petmarketplace.domain.user.entity.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Covers module §8 (reviews) of AUTOTESTS_SPECIFICATION.md. */
class ReviewControllerTest extends IntegrationTestBase {

    @Test
    void buyerCreatesPendingReviewAfterCompletedBooking() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser buyer = createUniqueUser(Role.BUYER);
        UUID bookingId = completedBooking(seller, admin, buyer);

        ResponseEntity<String> res = postStatus("/reviews",
                new ReviewCreateRequest(bookingId, 5, "Great"), buyer);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(res).get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void nonBuyerCannotReview() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser buyer = createUniqueUser(Role.BUYER);
        TestUser stranger = createUniqueUser(Role.BUYER);
        UUID bookingId = completedBooking(seller, admin, buyer);

        ResponseEntity<String> res = postStatus("/reviews",
                new ReviewCreateRequest(bookingId, 5, "x"), stranger);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void cannotReviewNonCompletedBooking() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser buyer = createUniqueUser(Role.BUYER);
        ListingResponse listing = createActiveListing(seller, admin);

        // Only book (PENDING), do not confirm/complete.
        ResponseEntity<BookingResponse> book = restClient.post()
                .uri("/listings/" + listing.id() + "/book")
                .headers(authHeaders(buyer))
                .retrieve()
                .toEntity(BookingResponse.class);
        assertThat(book.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> res = postStatus("/reviews",
                new ReviewCreateRequest(book.getBody().id(), 5, "x"), buyer);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void duplicateReviewRejected() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser buyer = createUniqueUser(Role.BUYER);
        UUID bookingId = completedBooking(seller, admin, buyer);

        postStatus("/reviews", new ReviewCreateRequest(bookingId, 5, "first"), buyer);
        ResponseEntity<String> second = postStatus("/reviews",
                new ReviewCreateRequest(bookingId, 4, "second"), buyer);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void reviewValidationErrors() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser buyer = createUniqueUser(Role.BUYER);
        UUID bookingId = completedBooking(seller, admin, buyer);

        assertThat(postStatus("/reviews", new ReviewCreateRequest(null, 5, "x"), buyer).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postStatus("/reviews", new ReviewCreateRequest(bookingId, 0, "x"), buyer).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postStatus("/reviews", new ReviewCreateRequest(bookingId, 6, "x"), buyer).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postStatus("/reviews", new ReviewCreateRequest(bookingId, 5, "x".repeat(2001)), buyer).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownBookingNotFound() {
        TestUser buyer = createUniqueUser(Role.BUYER);
        assertThat(postStatus("/reviews",
                new ReviewCreateRequest(UUID.randomUUID(), 5, "x"), buyer).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void adminRoleCannotCreateReview() {
        // @PreAuthorize hasAnyRole('BUYER','SELLER') -> ADMIN gets 403.
        TestUser admin = createUniqueUser(Role.ADMIN);
        assertThat(postStatus("/reviews",
                new ReviewCreateRequest(UUID.randomUUID(), 5, "x"), admin).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void reviewRequiresAuth() {
        assertThat(postStatus("/reviews",
                new ReviewCreateRequest(UUID.randomUUID(), 5, "x"), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void strangerSeesOnlyApprovedReviews() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser buyer = createUniqueUser(Role.BUYER);
        TestUser stranger = createUniqueUser(Role.BUYER);

        UUID reviewId = createPendingReviewId(seller, admin, buyer);
        // A stranger's view of seller's reviews must NOT include the pending review.
        ResponseEntity<String> res = getStatus("/reviews/" + seller.id(), stranger);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(res).get("content").findValuesAsText("id"))
                .doesNotContain(reviewId.toString());
        assertThat(parse(res).get("content").findValuesAsText("status"))
                .allMatch(s -> s.equals("APPROVED"));
    }

    @Test
    void selfSeesPendingReviews() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser buyer = createUniqueUser(Role.BUYER);

        UUID reviewId = createPendingReviewId(seller, admin, buyer);
        // The seller (recipient) sees their own pending review.
        ResponseEntity<String> res = getStatus("/reviews/" + seller.id(), seller);
        assertThat(parse(res).get("content").findValuesAsText("id"))
                .contains(reviewId.toString());
    }

    @Test
    void adminSeesPendingReviews() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser buyer = createUniqueUser(Role.BUYER);

        UUID reviewId = createPendingReviewId(seller, admin, buyer);
        ResponseEntity<String> res = getStatus("/reviews/" + seller.id(), admin);
        assertThat(parse(res).get("content").findValuesAsText("id"))
                .contains(reviewId.toString());
    }

    @Test
    void reviewsEndpointRequiresAuth() {
        TestUser seller = createUniqueUser(Role.SELLER);
        assertThat(getStatus("/reviews/" + seller.id(), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------------

    private UUID completedBooking(TestUser seller, TestUser admin, TestUser buyer) {
        ListingResponse listing = createActiveListing(seller, admin);
        ResponseEntity<BookingResponse> book = restClient.post()
                .uri("/listings/" + listing.id() + "/book")
                .headers(authHeaders(buyer))
                .retrieve()
                .toEntity(BookingResponse.class);
        UUID bookingId = book.getBody().id();
        restClient.put().uri("/bookings/" + bookingId + "/confirm")
                .headers(authHeaders(seller)).retrieve().toBodilessEntity();
        restClient.put().uri("/bookings/" + bookingId + "/complete")
                .headers(authHeaders(seller)).retrieve().toBodilessEntity();
        return bookingId;
    }

    private UUID createPendingReviewId(TestUser seller, TestUser admin, TestUser buyer) {
        UUID bookingId = completedBooking(seller, admin, buyer);
        ResponseEntity<String> res = postStatus("/reviews",
                new ReviewCreateRequest(bookingId, 5, "x"), buyer);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(res).get("status").asText()).isEqualTo(ReviewStatus.PENDING.name());
        return UUID.fromString(parse(res).get("id").asText());
    }

    private JsonNode parse(ResponseEntity<String> res) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}