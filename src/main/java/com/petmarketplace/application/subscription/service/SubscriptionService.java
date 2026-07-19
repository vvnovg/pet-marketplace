package com.petmarketplace.application.subscription.service;

import com.petmarketplace.application.subscription.dto.SubscriptionCreateRequest;
import com.petmarketplace.application.subscription.dto.SubscriptionResponse;
import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingGender;
import com.petmarketplace.domain.subscription.entity.Subscription;
import com.petmarketplace.domain.subscription.entity.SubscriptionFilters;
import com.petmarketplace.domain.subscription.repository.SubscriptionRepository;
import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.domain.user.repository.UserRepository;
import com.petmarketplace.exception.BusinessException;
import com.petmarketplace.exception.ResourceNotFoundException;
import com.petmarketplace.infrastructure.notification.EmailNotificationService;
import com.petmarketplace.infrastructure.security.UserDetailsImpl;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final EmailNotificationService emailNotificationService;

    public SubscriptionResponse createSubscription(SubscriptionCreateRequest request) {
        User user = currentUser();
        SubscriptionFilters filters = toFilters(request);

        Subscription subscription = Subscription.builder()
                .user(user)
                .filters(filters)
                .active(true)
                .build();
        Subscription saved = subscriptionRepository.save(subscription);
        log.debug("User {} created subscription {}", user.getId(), saved.getId());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionResponse> getSubscriptions() {
        User user = currentUser();
        return subscriptionRepository.findByUserId(user.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    public void deleteSubscription(UUID subscriptionId) {
        Subscription subscription = findSubscription(subscriptionId);
        requireOwner(subscription);
        subscriptionRepository.delete(subscription);
        log.debug("User {} deleted subscription {}", currentUser().getId(), subscriptionId);
    }

    public SubscriptionResponse deactivateSubscription(UUID subscriptionId) {
        Subscription subscription = findSubscription(subscriptionId);
        requireOwner(subscription);
        subscription.setActive(false);
        Subscription saved = subscriptionRepository.save(subscription);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<Subscription> findMatchingActiveSubscriptions(Listing listing) {
        return subscriptionRepository.findByActiveTrue().stream()
                .filter(sub -> sub.getFilters() != null && matches(listing, sub.getFilters()))
                .toList();
    }

    public void notifyMatchingSubscribers(Listing listing) {
        findMatchingActiveSubscriptions(listing).forEach(subscription -> {
            try {
                emailNotificationService.sendSubscriptionMatch(subscription.getUser(), listing);
                log.debug("Notified user {} about matching listing {}",
                        subscription.getUser().getId(), listing.getId());
            } catch (RuntimeException ex) {
                log.error("Failed to send subscription match to user {} for listing {}",
                        subscription.getUser().getId(), listing.getId(), ex);
            }
        });
    }

    private SubscriptionFilters toFilters(SubscriptionCreateRequest request) {
        return SubscriptionFilters.builder()
                .categoryId(request.categoryId())
                .breedId(request.breedId())
                .city(request.city())
                .minPrice(request.minPrice())
                .maxPrice(request.maxPrice())
                .gender(request.gender())
                .minAge(request.minAge())
                .maxAge(request.maxAge())
                .hasVaccination(request.hasVaccination())
                .hasDocuments(request.hasDocuments())
                .build();
    }

    private SubscriptionResponse toResponse(Subscription subscription) {
        return new SubscriptionResponse(
                subscription.getId(),
                subscription.getFilters(),
                subscription.isActive(),
                subscription.getCreatedAt()
        );
    }

    private Subscription findSubscription(UUID subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription not found", subscriptionId));
    }

    private void requireOwner(Subscription subscription) {
        User user = currentUser();
        if (!Objects.equals(user.getId(), subscription.getUser().getId())) {
            throw new BusinessException("You are not allowed to manage this subscription");
        }
    }

    private boolean matches(Listing listing, SubscriptionFilters filters) {
        if (filters.getCategoryId() != null
                && !Objects.equals(listing.getCategory().getId(), filters.getCategoryId())) {
            return false;
        }
        if (filters.getBreedId() != null
                && (listing.getBreed() == null || !Objects.equals(listing.getBreed().getId(), filters.getBreedId()))) {
            return false;
        }
        if (hasText(filters.getCity())
                && !equalsIgnoreCase(listing.getLocationCity(), filters.getCity())) {
            return false;
        }
        if (filters.getMinPrice() != null
                && (listing.getPrice() == null || listing.getPrice().compareTo(filters.getMinPrice()) < 0)) {
            return false;
        }
        if (filters.getMaxPrice() != null
                && (listing.getPrice() == null || listing.getPrice().compareTo(filters.getMaxPrice()) > 0)) {
            return false;
        }
        if (filters.getGender() != null && !Objects.equals(listing.getGender(), filters.getGender())) {
            return false;
        }
        if (filters.getMinAge() != null
                && (listing.getAgeMonths() == null || listing.getAgeMonths() < filters.getMinAge())) {
            return false;
        }
        if (filters.getMaxAge() != null
                && (listing.getAgeMonths() == null || listing.getAgeMonths() > filters.getMaxAge())) {
            return false;
        }
        if (Boolean.TRUE.equals(filters.getHasVaccination()) && !Boolean.TRUE.equals(listing.getHasVaccination())) {
            return false;
        }
        if (Boolean.TRUE.equals(filters.getHasDocuments()) && !Boolean.TRUE.equals(listing.getHasDocuments())) {
            return false;
        }
        return true;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) {
            return Objects.equals(a, b);
        }
        return a.equalsIgnoreCase(b);
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
}
