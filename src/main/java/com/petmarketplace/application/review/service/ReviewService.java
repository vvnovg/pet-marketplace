package com.petmarketplace.application.review.service;

import com.petmarketplace.application.review.dto.ReviewCreateRequest;
import com.petmarketplace.application.review.dto.ReviewModerateRequest;
import com.petmarketplace.application.review.dto.ReviewResponse;
import com.petmarketplace.application.review.mapper.ReviewMapper;
import com.petmarketplace.domain.booking.entity.Booking;
import com.petmarketplace.domain.booking.entity.BookingStatus;
import com.petmarketplace.domain.booking.repository.BookingRepository;
import com.petmarketplace.domain.review.entity.Review;
import com.petmarketplace.domain.review.entity.ReviewStatus;
import com.petmarketplace.domain.review.repository.ReviewRepository;
import com.petmarketplace.domain.user.entity.Profile;
import com.petmarketplace.domain.user.entity.Role;
import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.domain.user.repository.ProfileRepository;
import com.petmarketplace.domain.user.repository.UserRepository;
import com.petmarketplace.exception.BusinessException;
import com.petmarketplace.exception.ResourceNotFoundException;
import com.petmarketplace.exception.ValidationException;
import com.petmarketplace.infrastructure.security.UserDetailsImpl;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final ReviewMapper reviewMapper;

    public ReviewResponse createReview(User author, ReviewCreateRequest request) {
        Booking booking = bookingRepository.findById(request.bookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found", request.bookingId()));

        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new BusinessException("Review can only be created for completed bookings");
        }
        if (!Objects.equals(author.getId(), booking.getBuyer().getId())) {
            throw new BusinessException("Only the buyer can leave a review for this booking");
        }
        if (reviewRepository.existsByBookingId(booking.getId())) {
            throw new BusinessException("Review already exists for this booking");
        }
        if (request.rating() < 1 || request.rating() > 5) {
            throw new ValidationException("Rating must be between 1 and 5");
        }

        User recipient = booking.getSeller();

        Review review = Review.builder()
                .author(author)
                .recipient(recipient)
                .booking(booking)
                .rating(request.rating())
                .comment(request.comment())
                .status(ReviewStatus.PENDING)
                .build();

        Review saved = reviewRepository.save(review);
        log.debug("Created review {} for booking {} by author {}", saved.getId(), booking.getId(), author.getId());
        return reviewMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponse> getReviewsByRecipient(UUID recipientId, Pageable pageable) {
        User currentUser = getAuthenticatedUser().orElse(null);
        boolean includePending = currentUser != null
                && (Objects.equals(currentUser.getId(), recipientId)
                || isAdminOrModerator(currentUser));

        Page<Review> reviews = includePending
                ? reviewRepository.findByRecipientId(recipientId, pageable)
                : reviewRepository.findByRecipientIdAndStatus(recipientId, ReviewStatus.APPROVED, pageable);

        return reviews.map(reviewMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponse> getReviewsForModeration(ReviewStatus status, Pageable pageable) {
        ReviewStatus targetStatus = status != null ? status : ReviewStatus.PENDING;
        return reviewRepository.findByStatus(targetStatus, pageable)
                .map(reviewMapper::toResponse);
    }

    public ReviewResponse moderateReview(User moderator, UUID reviewId, ReviewModerateRequest request) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found", reviewId));

        if (request.status() == ReviewStatus.PENDING) {
            throw new ValidationException("Cannot set review status back to PENDING");
        }

        ReviewStatus previousStatus = review.getStatus();
        review.setStatus(request.status());
        Review saved = reviewRepository.save(review);

        if (saved.getStatus() == ReviewStatus.APPROVED && previousStatus != ReviewStatus.APPROVED) {
            recalculateRating(saved.getRecipient().getId());
        }

        log.debug("Moderated review {} to status {} by moderator {}",
                saved.getId(), saved.getStatus(), moderator.getId());
        return reviewMapper.toResponse(saved);
    }

    public void recalculateRating(UUID recipientId) {
        Double average = reviewRepository.calculateAverageRatingByRecipientIdAndStatus(
                recipientId, ReviewStatus.APPROVED);
        Long total = reviewRepository.countByRecipientIdAndStatus(recipientId, ReviewStatus.APPROVED);

        BigDecimal rating = average == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(average).setScale(1, RoundingMode.HALF_UP);
        int totalReviews = total == null ? 0 : total.intValue();

        Profile profile = profileRepository.findByUserId(recipientId)
                .orElseGet(() -> createEmptyProfile(recipientId));

        profile.setRating(rating);
        profile.setTotalReviews(totalReviews);
        profileRepository.save(profile);

        log.debug("Recalculated rating for user {}: rating={}, totalReviews={}", recipientId, rating, totalReviews);
    }

    private Profile createEmptyProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found", userId));
        return Profile.builder()
                .user(user)
                .rating(BigDecimal.ZERO)
                .totalReviews(0)
                .build();
    }

    private Optional<User> getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserDetailsImpl details)) {
            return Optional.empty();
        }
        return userRepository.findByEmail(details.getEmail());
    }

    private boolean isAdminOrModerator(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.MODERATOR;
    }
}
