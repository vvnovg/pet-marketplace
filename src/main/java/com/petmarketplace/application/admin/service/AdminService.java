package com.petmarketplace.application.admin.service;

import com.petmarketplace.application.admin.dto.AdminStatisticsResponse;
import com.petmarketplace.application.admin.dto.AdminUserResponse;
import com.petmarketplace.application.admin.dto.ListingModerateRequest;
import com.petmarketplace.application.admin.dto.UserRoleUpdateRequest;
import com.petmarketplace.application.admin.dto.UserStatusUpdateRequest;
import com.petmarketplace.application.admin.mapper.AdminMapper;
import com.petmarketplace.application.listing.dto.ListingResponse;
import com.petmarketplace.application.listing.mapper.ListingMapper;
import com.petmarketplace.application.review.dto.ReviewModerateRequest;
import com.petmarketplace.application.review.dto.ReviewResponse;
import com.petmarketplace.application.review.service.ReviewService;
import com.petmarketplace.application.subscription.service.SubscriptionService;
import com.petmarketplace.domain.booking.entity.BookingStatus;
import com.petmarketplace.domain.booking.repository.BookingRepository;
import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.listing.repository.ListingRepository;
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
import com.petmarketplace.infrastructure.notification.EmailNotificationService;
import com.petmarketplace.infrastructure.security.UserDetailsImpl;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final ListingRepository listingRepository;
    private final BookingRepository bookingRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewService reviewService;
    private final SubscriptionService subscriptionService;
    private final EmailNotificationService emailNotificationService;
    private final AdminMapper adminMapper;
    private final ListingMapper listingMapper;

    @Transactional(readOnly = true)
    public Page<AdminUserResponse> listUsers(Role role, Boolean active, Boolean verified, String search, Pageable pageable) {
        Specification<User> spec = UserSpecification.buildAdminSearch(role, active, verified, search);
        Page<User> users = userRepository.findAll(spec, pageable);
        Map<UUID, Profile> profiles = loadProfiles(users.getContent());
        return users.map(user -> adminMapper.toAdminUserResponse(profiles.get(user.getId()), user));
    }

    public AdminUserResponse updateUserStatus(UUID userId, UserStatusUpdateRequest request) {
        User admin = currentUser();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found", userId));

        boolean previousActive = user.isActive();
        user.setActive(request.active());
        User saved = userRepository.save(user);

        log.info("Admin {} changed status of user {} from active={} to active={} (reason: {})",
                admin.getId(), saved.getId(), previousActive, saved.isActive(), request.reason());

        return toAdminUserResponse(saved);
    }

    public AdminUserResponse updateUserRole(UUID userId, UserRoleUpdateRequest request) {
        User admin = currentUser();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found", userId));

        Role previousRole = user.getRole();
        user.setRole(request.role());
        User saved = userRepository.save(user);

        log.info("Admin {} changed role of user {} from {} to {}",
                admin.getId(), saved.getId(), previousRole, saved.getRole());

        return toAdminUserResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<ListingResponse> getPendingListings(Pageable pageable, Locale locale) {
        List<ListingStatus> pendingStatuses = List.of(ListingStatus.PENDING_MODERATION, ListingStatus.REJECTED);
        Page<Listing> listings = listingRepository.findByStatusIn(pendingStatuses, pageable);
        Map<UUID, Profile> profiles = loadProfilesByUserIds(listings.getContent().stream()
                .map(l -> l.getSeller().getId())
                .distinct()
                .toList());

        return listings.map(listing -> {
            Profile profile = profiles.get(listing.getSeller().getId());
            return listingMapper.toListingResponse(listing, profile, locale);
        });
    }

    public ListingResponse moderateListing(UUID listingId, ListingModerateRequest request, Locale locale) {
        User admin = currentUser();
        Listing listing = listingRepository.findByIdWithSeller(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing not found", listingId));

        if (listing.getStatus() != ListingStatus.PENDING_MODERATION && listing.getStatus() != ListingStatus.REJECTED) {
            throw new BusinessException("Only listings with status PENDING_MODERATION or REJECTED can be moderated");
        }
        if (request.status() != ListingStatus.ACTIVE && request.status() != ListingStatus.REJECTED) {
            throw new ValidationException("Moderation result must be ACTIVE or REJECTED");
        }

        ListingStatus previousStatus = listing.getStatus();
        listing.setStatus(request.status());
        Listing saved = listingRepository.save(listing);

        emailNotificationService.sendListingStatusUpdate(saved.getSeller(), saved, saved.getStatus());
        if (saved.getStatus() == ListingStatus.ACTIVE) {
            subscriptionService.notifyMatchingSubscribers(saved);
        }

        log.info("Admin {} moderated listing {} from {} to {} (reason: {})",
                admin.getId(), saved.getId(), previousStatus, saved.getStatus(), request.reason());

        Profile sellerProfile = profileRepository.findByUserId(saved.getSeller().getId()).orElse(null);
        return listingMapper.toListingResponse(saved, sellerProfile, locale);
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponse> getPendingReviews(Pageable pageable) {
        return reviewService.getReviewsForModeration(ReviewStatus.PENDING, pageable);
    }

    public ReviewResponse moderateReview(UUID reviewId, ReviewModerateRequest request) {
        User admin = currentUser();
        ReviewResponse response = reviewService.moderateReview(admin, reviewId, request);
        log.info("Admin {} moderated review {} to status {} (reason: {})",
                admin.getId(), reviewId, response.status(), request.reason());
        return response;
    }

    @Transactional(readOnly = true)
    public AdminStatisticsResponse getStatistics() {
        long totalUsers = userRepository.count();
        long activeUsers = userRepository.countByActiveTrue();

        Map<ListingStatus, Long> listingsByStatus = Arrays.stream(ListingStatus.values())
                .collect(Collectors.toMap(Function.identity(), status -> listingRepository.countByStatus(status)));

        Map<BookingStatus, Long> bookingsByStatus = Arrays.stream(BookingStatus.values())
                .collect(Collectors.toMap(Function.identity(), status -> bookingRepository.countByStatus(status)));

        Map<ReviewStatus, Long> reviewsByStatus = Arrays.stream(ReviewStatus.values())
                .collect(Collectors.toMap(Function.identity(), status -> reviewRepository.countByStatus(status)));

        long listingsToday = listingRepository.countCreatedSince(startOfToday());
        long listingsThisWeek = listingRepository.countCreatedSince(startOfWeek());
        long listingsThisMonth = listingRepository.countCreatedSince(startOfMonth());

        return new AdminStatisticsResponse(
                totalUsers,
                activeUsers,
                listingsByStatus,
                bookingsByStatus,
                reviewsByStatus,
                listingsToday,
                listingsThisWeek,
                listingsThisMonth
        );
    }

    private AdminUserResponse toAdminUserResponse(User user) {
        Profile profile = profileRepository.findByUserId(user.getId()).orElse(null);
        return adminMapper.toAdminUserResponse(profile, user);
    }

    private Map<UUID, Profile> loadProfiles(Collection<User> users) {
        List<UUID> userIds = users.stream()
                .map(User::getId)
                .distinct()
                .toList();
        return loadProfilesByUserIds(userIds);
    }

    private Map<UUID, Profile> loadProfilesByUserIds(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return profileRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.toMap(p -> p.getUser().getId(), Function.identity(), (a, b) -> a));
    }

    private User currentUser() {
        return getAuthenticatedUser()
                .orElseThrow(() -> new BusinessException("User not authenticated"));
    }

    private Optional<User> getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserDetailsImpl details)) {
            return Optional.empty();
        }
        return userRepository.findByEmail(details.getEmail());
    }

    private Instant startOfToday() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant startOfWeek() {
        return LocalDate.now(ZoneOffset.UTC)
                .with(DayOfWeek.MONDAY)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
    }

    private Instant startOfMonth() {
        return LocalDate.now(ZoneOffset.UTC)
                .withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
    }
}
