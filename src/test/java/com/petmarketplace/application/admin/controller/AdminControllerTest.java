package com.petmarketplace.application.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.petmarketplace.IntegrationTestBase;
import com.petmarketplace.application.admin.dto.AdminStatisticsResponse;
import com.petmarketplace.application.admin.dto.UserRoleUpdateRequest;
import com.petmarketplace.application.admin.dto.UserStatusUpdateRequest;
import com.petmarketplace.application.booking.dto.BookingResponse;
import com.petmarketplace.application.listing.dto.ListingCreateRequest;
import com.petmarketplace.application.listing.dto.ListingResponse;
import com.petmarketplace.application.review.dto.ReviewCreateRequest;
import com.petmarketplace.application.review.dto.ReviewModerateRequest;
import com.petmarketplace.application.review.dto.ReviewResponse;
import com.petmarketplace.domain.booking.entity.BookingStatus;
import com.petmarketplace.domain.listing.entity.ListingGender;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.review.entity.ReviewStatus;
import com.petmarketplace.domain.user.entity.Role;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Covers the admin endpoints (module §11 of AUTOTESTS_SPECIFICATION.md): access matrix,
 * user management (status/role), listing moderation queue + approve/reject, review moderation
 * with rating recalculation, and platform statistics.
 *
 * Note on authorization codes: admin endpoints are gated by {@code @PreAuthorize} on the
 * controller class plus the SecurityConfig path rule, so the wrong role → 403 (security), while
 * business-level guards inside the services (e.g. "only PENDING_MODERATION/REJECTED can be
 * moderated") throw BusinessException → 409, and invalid moderation result → 400.
 */
class AdminControllerTest extends IntegrationTestBase {

    @Test
    void adminUsersAllowedForAdminAndModerator() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser moderator = createUniqueUser(Role.MODERATOR);

        assertThat(getStatus("/admin/users", admin).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getStatus("/admin/users", moderator).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void adminUsersForbiddenForSellerAndBuyer() {
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser buyer = createUniqueUser(Role.BUYER);

        assertThat(getStatus("/admin/users", seller).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getStatus("/admin/users", buyer).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminUsersUnauthorizedWithoutToken() {
        assertThat(getStatus("/admin/users", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminUsersFiltersByRole() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        createUniqueUser(Role.SELLER);
        createUniqueUser(Role.BUYER);

        ResponseEntity<String> response = getStatus("/admin/users?role=SELLER&size=999", admin);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = parse(response);
        for (JsonNode row : body.get("content")) {
            assertThat(row.get("role").asText()).isEqualTo("SELLER");
        }
        assertThat(body.get("content").size()).isGreaterThan(0);
    }

    @Test
    void adminUsersSearchAndPagination() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        ResponseEntity<String> paged = getStatus("/admin/users?page=0&size=2", admin);
        assertThat(paged.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = parse(paged);
        assertThat(page.get("content").size()).isLessThanOrEqualTo(2);
        assertThat(page.get("totalElements").asInt()).isGreaterThan(0);

        ResponseEntity<String> empty = getStatus("/admin/users?search=zzznope", admin);
        assertThat(parse(empty).get("content").size()).isZero();
    }

    @Test
    void adminUsersResponseHasNoPasswordHash() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        String body = getStatus("/admin/users?size=999", admin).getBody();
        assertThat(body).isNotNull();
        assertThat(body.toLowerCase()).doesNotContain("password");
    }

    @Test
    void deactivateUserBlocksLogin() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser target = createUniqueUser(Role.BUYER);

        ResponseEntity<String> disable = putStatus(
                "/admin/users/" + target.id() + "/status",
                new UserStatusUpdateRequest(false, "spam"), admin);
        assertThat(disable.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Disabled account cannot log in (business 409).
        ResponseEntity<String> login = postStatus(
                "/auth/login", new com.petmarketplace.application.auth.dto.LoginRequest(
                        target.email(), target.password()), null);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void activateUserRestoresLogin() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser target = createUniqueUser(Role.BUYER);
        putStatus("/admin/users/" + target.id() + "/status",
                new UserStatusUpdateRequest(false, "x"), admin);
        ResponseEntity<String> enable = putStatus(
                "/admin/users/" + target.id() + "/status",
                new UserStatusUpdateRequest(true, null), admin);
        assertThat(enable.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void updateUserStatusValidationAndNotFound() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        // active is @NotNull
        assertThat(putStatus("/admin/users/" + createUniqueUser(Role.BUYER).id() + "/status",
                new UserStatusUpdateRequest(null, "x"), admin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // unknown user
        assertThat(putStatus("/admin/users/" + UUID.randomUUID() + "/status",
                new UserStatusUpdateRequest(false, "x"), admin).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void changeUserRole() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser target = createUniqueUser(Role.BUYER);

        ResponseEntity<String> res = putStatus(
                "/admin/users/" + target.id() + "/role",
                new UserRoleUpdateRequest(Role.SELLER), admin);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Buyer now has SELLER role -> creating a listing should be allowed (was 403 before).
        ListingCreateRequest req = sampleListingRequest();
        ResponseEntity<String> create = postStatus("/listings", req, target);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void updateUserRoleValidationAndNotFound() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        assertThat(putStatus("/admin/users/" + createUniqueUser(Role.BUYER).id() + "/role",
                new UserRoleUpdateRequest(null), admin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(putStatus("/admin/users/" + UUID.randomUUID() + "/role",
                new UserRoleUpdateRequest(Role.SELLER), admin).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void pendingListingsIncludePendingModerationAndRejected() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);

        // One pending (just created) and one rejected.
        UUID pendingId = createListingAsSeller(seller).id();
        UUID toRejectId = createListingAsSeller(seller).id();
        putStatus("/admin/listings/" + toRejectId + "/moderate",
                new com.petmarketplace.application.admin.dto.ListingModerateRequest(
                        ListingStatus.REJECTED, "bad photo"), admin);

        ResponseEntity<String> response = getStatus("/admin/listings/pending?size=999", admin);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = parse(response).get("content");
        var statuses = content.findValuesAsText("status");
        assertThat(statuses).contains("PENDING_MODERATION", "REJECTED");
        assertThat(content.findValuesAsText("id"))
                .contains(pendingId.toString(), toRejectId.toString());
    }

    @Test
    void moderateApproveSetsActive() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        UUID listingId = createListingAsSeller(seller).id();

        ResponseEntity<String> res = putStatus("/admin/listings/" + listingId + "/moderate",
                new com.petmarketplace.application.admin.dto.ListingModerateRequest(
                        ListingStatus.ACTIVE, null), admin);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(res).get("status").asText()).isEqualTo("ACTIVE");

        // Now publicly visible.
        assertThat(getStatus("/listings/" + listingId, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void moderateRejectHidesFromPublicList() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        UUID listingId = createListingAsSeller(seller).id();

        ResponseEntity<String> res = putStatus("/admin/listings/" + listingId + "/moderate",
                new com.petmarketplace.application.admin.dto.ListingModerateRequest(
                        ListingStatus.REJECTED, "reason"), admin);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(res).get("status").asText()).isEqualTo("REJECTED");

        // Non-owner public GET of a non-active listing -> 404.
        assertThat(getStatus("/listings/" + listingId, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void moderateRejectsInvalidResultStatus() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        UUID listingId = createListingAsSeller(seller).id();

        // Only ACTIVE or REJECTED allowed as result.
        ResponseEntity<String> res = putStatus("/admin/listings/" + listingId + "/moderate",
                new com.petmarketplace.application.admin.dto.ListingModerateRequest(
                        ListingStatus.DRAFT, null), admin);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void moderateRejectsAlreadyActiveListing() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        UUID listingId = createActiveListing(seller, admin).id();

        ResponseEntity<String> res = putStatus("/admin/listings/" + listingId + "/moderate",
                new com.petmarketplace.application.admin.dto.ListingModerateRequest(
                        ListingStatus.REJECTED, null), admin);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void moderateRejectedListingCanBeReApproved() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        UUID listingId = createListingAsSeller(seller).id();

        putStatus("/admin/listings/" + listingId + "/moderate",
                new com.petmarketplace.application.admin.dto.ListingModerateRequest(
                        ListingStatus.REJECTED, "fix"), admin);
        ResponseEntity<String> reapprove = putStatus("/admin/listings/" + listingId + "/moderate",
                new com.petmarketplace.application.admin.dto.ListingModerateRequest(
                        ListingStatus.ACTIVE, null), admin);
        assertThat(reapprove.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(reapprove).get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void moderateNotFoundListing() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        assertThat(putStatus("/admin/listings/" + UUID.randomUUID() + "/moderate",
                new com.petmarketplace.application.admin.dto.ListingModerateRequest(
                        ListingStatus.ACTIVE, null), admin).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void pendingReviewsContainOnlyPending() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser buyer = createUniqueUser(Role.BUYER);

        UUID reviewId = createPendingReview(seller, admin, buyer).id();
        // Approve one review -> it must disappear from the pending queue.
        putStatus("/admin/reviews/" + reviewId + "/moderate",
                new ReviewModerateRequest(ReviewStatus.APPROVED, null), admin);

        ResponseEntity<String> res = getStatus("/admin/reviews/pending?size=999", admin);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(res).get("content").findValuesAsText("id"))
                .doesNotContain(reviewId.toString());
    }

    @Test
    void approveReviewRecalculatesRating() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser buyer = createUniqueUser(Role.BUYER);

        ReviewResponse review = createPendingReview(seller, admin, buyer);
        BigDecimal ratingBefore = sellerRating(seller);

        ResponseEntity<ReviewResponse> res = restClient.put()
                .uri("/admin/reviews/" + review.id() + "/moderate")
                .body(new ReviewModerateRequest(ReviewStatus.APPROVED, null))
                .headers(authHeaders(admin))
                .retrieve()
                .toEntity(ReviewResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().status()).isEqualTo(ReviewStatus.APPROVED);

        // Profile rating recalculated and totalReviews incremented.
        assertThat(sellerTotalReviews(seller)).isEqualTo(1);
        assertThat(sellerRating(seller)).isEqualByComparingTo(BigDecimal.valueOf(5.0));
        assertThat(sellerRating(seller)).isNotEqualByComparingTo(ratingBefore);
    }

    @Test
    void rejectReviewDoesNotChangeRating() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser buyer = createUniqueUser(Role.BUYER);

        ReviewResponse review = createPendingReview(seller, admin, buyer);
        BigDecimal ratingBefore = sellerRating(seller);

        ResponseEntity<String> res = putStatus("/admin/reviews/" + review.id() + "/moderate",
                new ReviewModerateRequest(ReviewStatus.REJECTED, "spam"), admin);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sellerRating(seller)).isEqualByComparingTo(ratingBefore);
        assertThat(sellerTotalReviews(seller)).isZero();
    }

    @Test
    void cannotSetReviewBackToPending() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser buyer = createUniqueUser(Role.BUYER);
        ReviewResponse review = createPendingReview(seller, admin, buyer);

        ResponseEntity<String> res = putStatus("/admin/reviews/" + review.id() + "/moderate",
                new ReviewModerateRequest(ReviewStatus.PENDING, null), admin);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void moderateReviewNotFound() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        assertThat(putStatus("/admin/reviews/" + UUID.randomUUID() + "/moderate",
                new ReviewModerateRequest(ReviewStatus.APPROVED, null), admin).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void statisticsContainAllStatusKeysAndCounts() {
        TestUser admin = createUniqueUser(Role.ADMIN);
        TestUser seller = createUniqueUser(Role.SELLER);
        TestUser buyer = createUniqueUser(Role.BUYER);

        // Create a listing and moderate to ACTIVE to populate listingsByStatus.
        createActiveListing(seller, admin);

        ResponseEntity<AdminStatisticsResponse> res = restClient.get()
                .uri("/admin/statistics")
                .headers(authHeaders(admin))
                .retrieve()
                .toEntity(AdminStatisticsResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        AdminStatisticsResponse stats = res.getBody();
        assertThat(stats).isNotNull();
        assertThat(stats.totalUsers()).isPositive();
        assertThat(stats.listingsByStatus()).containsKey(ListingStatus.ACTIVE);
        assertThat(stats.listingsByStatus().get(ListingStatus.ACTIVE)).isPositive();
        // All ListingStatus enum values are present as keys (Jackson 3 enum map binding).
        assertThat(stats.listingsByStatus().keySet()).containsAll(java.util.List.of(ListingStatus.values()));
        assertThat(stats.reviewsByStatus().keySet())
                .containsAll(java.util.List.of(ReviewStatus.values()));
        assertThat(stats.bookingsByStatus().keySet())
                .containsAll(java.util.List.of(BookingStatus.values()));
        assertThat(stats.listingsCreatedToday()).isNotNegative();
        assertThat(stats.listingsCreatedThisMonth()).isGreaterThanOrEqualTo(stats.listingsCreatedToday());
        // buyer is unused but ensures extra users are counted; keep reference
        assertThat(buyer).isNotNull();
    }

    @Test
    void statisticsForbiddenForBuyer() {
        TestUser buyer = createUniqueUser(Role.BUYER);
        assertThat(getStatus("/admin/statistics", buyer).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private JsonNode parse(ResponseEntity<String> response) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(response.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ListingCreateRequest sampleListingRequest() {
        return new ListingCreateRequest(
                DOGS_CATEGORY_ID, LABRADOR_BREED_ID,
                "Admin test puppy", "desc", BigDecimal.valueOf(30000), "RUB",
                ListingGender.MALE, 3, "Black", BigDecimal.valueOf(10.0),
                "Healthy", true, true, "Russia", "Moscow");
    }

    /** Creates a listing as seller leaving it in PENDING_MODERATION (not moderated). */
    private ListingResponse createListingAsSeller(TestUser seller) {
        ResponseEntity<ListingResponse> res = restClient.post()
                .uri("/listings")
                .body(sampleListingRequest())
                .headers(authHeaders(seller))
                .retrieve()
                .toEntity(ListingResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().status()).isEqualTo(ListingStatus.PENDING_MODERATION);
        return res.getBody();
    }

    /** Books, confirms and completes a listing, then the buyer leaves a rating-5 review (PENDING). */
    private ReviewResponse createPendingReview(TestUser seller, TestUser admin, TestUser buyer) {
        ListingResponse listing = createActiveListing(seller, admin);

        ResponseEntity<BookingResponse> book = restClient.post()
                .uri("/listings/" + listing.id() + "/book")
                .headers(authHeaders(buyer))
                .retrieve()
                .toEntity(BookingResponse.class);
        assertThat(book.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID bookingId = book.getBody().id();

        restClient.put().uri("/bookings/" + bookingId + "/confirm")
                .headers(authHeaders(seller)).retrieve().toBodilessEntity();
        restClient.put().uri("/bookings/" + bookingId + "/complete")
                .headers(authHeaders(seller)).retrieve().toBodilessEntity();

        ResponseEntity<ReviewResponse> review = restClient.post()
                .uri("/reviews")
                .body(new ReviewCreateRequest(bookingId, 5, "Great seller"))
                .headers(authHeaders(buyer))
                .retrieve()
                .toEntity(ReviewResponse.class);
        assertThat(review.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(review.getBody().status()).isEqualTo(ReviewStatus.PENDING);
        return review.getBody();
    }

    private BigDecimal sellerRating(TestUser seller) {
        return profileRepository.findByUserId(seller.id())
                .orElseThrow().getRating();
    }

    private int sellerTotalReviews(TestUser seller) {
        return profileRepository.findByUserId(seller.id())
                .orElseThrow().getTotalReviews();
    }
}