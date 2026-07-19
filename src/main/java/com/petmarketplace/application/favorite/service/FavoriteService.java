package com.petmarketplace.application.favorite.service;

import com.petmarketplace.application.favorite.dto.FavoriteResponse;
import com.petmarketplace.application.listing.mapper.ListingMapper;
import com.petmarketplace.domain.favorite.entity.Favorite;
import com.petmarketplace.domain.favorite.repository.FavoriteRepository;
import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.repository.ListingRepository;
import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.domain.user.repository.UserRepository;
import com.petmarketplace.exception.BusinessException;
import com.petmarketplace.exception.ResourceNotFoundException;
import com.petmarketplace.infrastructure.security.UserDetailsImpl;
import java.util.List;
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
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final ListingMapper listingMapper;

    public void addToFavorites(UUID listingId) {
        User user = currentUser();
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing not found", listingId));

        if (favoriteRepository.existsByUserIdAndListingId(user.getId(), listingId)) {
            log.debug("Listing {} is already in favorites for user {}", listingId, user.getId());
            return;
        }

        Favorite favorite = Favorite.builder()
                .user(user)
                .listing(listing)
                .build();
        favoriteRepository.save(favorite);
        log.debug("User {} added listing {} to favorites", user.getId(), listingId);
    }

    public void removeFromFavorites(UUID listingId) {
        User user = currentUser();
        if (!listingRepository.existsById(listingId)) {
            throw new ResourceNotFoundException("Listing not found", listingId);
        }
        favoriteRepository.deleteByUserIdAndListingId(user.getId(), listingId);
        log.debug("User {} removed listing {} from favorites", user.getId(), listingId);
    }

    @Transactional(readOnly = true)
    public List<FavoriteResponse> getFavorites() {
        User user = currentUser();
        return favoriteRepository.findByUserIdWithListings(user.getId()).stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isFavorite(UUID listingId) {
        User user = getCurrentUserOrNull();
        if (user == null) {
            return false;
        }
        return favoriteRepository.existsByUserIdAndListingId(user.getId(), listingId);
    }

    private FavoriteResponse mapToResponse(Favorite favorite) {
        return new FavoriteResponse(
                favorite.getId(),
                listingMapper.toMiniResponse(favorite.getListing()),
                favorite.getCreatedAt()
        );
    }

    private User currentUser() {
        return getAuthenticatedUser()
                .orElseThrow(() -> new BusinessException("User not authenticated"));
    }

    private User getCurrentUserOrNull() {
        return getAuthenticatedUser().orElse(null);
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
