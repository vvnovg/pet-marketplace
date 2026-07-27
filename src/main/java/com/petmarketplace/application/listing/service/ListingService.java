package com.petmarketplace.application.listing.service;

import com.petmarketplace.application.listing.dto.ListingCreateRequest;
import com.petmarketplace.application.listing.dto.ListingImageResponse;
import com.petmarketplace.application.listing.dto.ListingResponse;
import com.petmarketplace.application.listing.dto.ListingSearchRequest;
import com.petmarketplace.application.listing.dto.ListingStatusUpdateRequest;
import com.petmarketplace.application.listing.mapper.ListingMapper;
import com.petmarketplace.domain.category.entity.Breed;
import com.petmarketplace.domain.category.entity.Category;
import com.petmarketplace.domain.category.repository.BreedRepository;
import com.petmarketplace.domain.category.repository.CategoryRepository;
import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingImage;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.listing.repository.ListingImageRepository;
import com.petmarketplace.domain.listing.repository.ListingRepository;
import com.petmarketplace.domain.user.entity.Profile;
import com.petmarketplace.domain.user.entity.Role;
import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.domain.user.repository.ProfileRepository;
import com.petmarketplace.domain.user.repository.UserRepository;
import com.petmarketplace.exception.BusinessException;
import com.petmarketplace.exception.ResourceNotFoundException;
import com.petmarketplace.exception.ValidationException;
import com.petmarketplace.infrastructure.security.UserDetailsImpl;
import com.petmarketplace.infrastructure.storage.FileStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ListingService {

    private static final String IMAGES_BUCKET = "images";
    private static final String IMAGES_PREFIX = "listings/";
    private static final int MAX_IMAGES_PER_LISTING = 10;
    private static final long MAX_IMAGE_SIZE_BYTES = 5L * 1024 * 1024;

    private final ListingRepository listingRepository;
    private final ListingImageRepository listingImageRepository;
    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final CategoryRepository categoryRepository;
    private final BreedRepository breedRepository;
    private final FileStorageService fileStorageService;
    private final ListingMapper listingMapper;

    public ListingResponse createListing(ListingCreateRequest request, Locale locale) {
        User seller = currentUser();
        requireRole(seller, Role.SELLER);

        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found", request.categoryId()));

        Breed breed = null;
        if (request.breedId() != null) {
            breed = breedRepository.findById(request.breedId())
                    .orElseThrow(() -> new ResourceNotFoundException("Breed not found", request.breedId()));
        }

        Listing listing = listingMapper.toEntity(request);
        listing.setSeller(seller);
        listing.setCategory(category);
        listing.setBreed(breed);
        listing.setStatus(ListingStatus.PENDING_MODERATION);
        listing.setViewsCount(0);

        Listing saved = listingRepository.save(listing);
        Profile sellerProfile = findProfile(seller.getId());
        return listingMapper.toListingResponse(saved, sellerProfile, locale);
    }

    public ListingResponse updateListing(UUID listingId, ListingCreateRequest request, Locale locale) {
        User currentUser = currentUser();
        Listing listing = findListing(listingId);
        requireOwnerOrAdmin(currentUser, listing);

        if (request.categoryId() != null && !request.categoryId().equals(listing.getCategory().getId())) {
            Category category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found", request.categoryId()));
            listing.setCategory(category);
        }

        if (request.breedId() != null && (listing.getBreed() == null || !request.breedId().equals(listing.getBreed().getId()))) {
            Breed breed = breedRepository.findById(request.breedId())
                    .orElseThrow(() -> new ResourceNotFoundException("Breed not found", request.breedId()));
            listing.setBreed(breed);
        } else if (request.breedId() == null) {
            listing.setBreed(null);
        }

        listingMapper.updateListingFromRequest(request, listing);
        Listing saved = listingRepository.save(listing);
        Profile sellerProfile = findProfile(saved.getSeller().getId());
        return listingMapper.toListingResponse(saved, sellerProfile, locale);
    }

    public void deleteListing(UUID listingId) {
        User currentUser = currentUser();
        Listing listing = findListing(listingId);
        requireOwnerOrAdmin(currentUser, listing);

        for (ListingImage image : List.copyOf(listing.getImages())) {
            deleteStoredImage(image, listingId);
        }
        listingRepository.delete(listing);
    }

    public ListingResponse getListingById(UUID listingId, Locale locale) {
        Listing listing = listingRepository.findByIdWithSeller(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing not found", listingId));

        User currentUser = getCurrentUserOrNull();
        if (listing.getStatus() != ListingStatus.ACTIVE && !canModify(currentUser, listing)) {
            throw new ResourceNotFoundException("Listing not found", listingId);
        }

        listing.setViewsCount(listing.getViewsCount() + 1);
        listingRepository.save(listing);

        Profile sellerProfile = listing.getSeller().getProfile();
        return listingMapper.toListingResponse(listing, sellerProfile, locale);
    }

    @Transactional(readOnly = true)
    public Page<ListingResponse> searchListings(ListingSearchRequest request, Locale locale) {
        Specification<Listing> filters = ListingSpecification.buildFromRequest(request);
        Pageable pageable = buildPageable(request);

        Page<Listing> page = listingRepository.findActiveByFilters(filters, pageable);
        Map<UUID, Profile> profilesByUserId = loadProfiles(page.getContent());

        return page.map(listing -> {
            Profile sellerProfile = profilesByUserId.get(listing.getSeller().getId());
            return listingMapper.toListingResponse(listing, sellerProfile, locale);
        });
    }

    public ListingImageResponse uploadImage(UUID listingId, MultipartFile file) {
        User currentUser = currentUser();
        Listing listing = findListing(listingId);
        requireOwnerOrAdmin(currentUser, listing);

        validateImage(file);

        long imageCount = listingImageRepository.countByListingId(listingId);
        if (imageCount >= MAX_IMAGES_PER_LISTING) {
            throw new ValidationException("Maximum " + MAX_IMAGES_PER_LISTING + " images per listing allowed");
        }

        String extension = getFileExtension(file.getOriginalFilename());
        String objectKey = IMAGES_PREFIX + listingId + "/" + UUID.randomUUID() + extension;

        String storedUrl;
        try (InputStream inputStream = file.getInputStream()) {
            storedUrl = fileStorageService.store(IMAGES_BUCKET, objectKey, inputStream,
                    file.getSize(), file.getContentType());
        } catch (IOException ex) {
            throw new BusinessException("Failed to read image file", ex);
        }

        if (!StringUtils.hasText(storedUrl)) {
            storedUrl = fileStorageService.getPublicUrl(IMAGES_BUCKET, objectKey);
        }

        ListingImage image = ListingImage.builder()
                .listing(listing)
                .url(storedUrl)
                .orderIndex((int) imageCount)
                .isMain(imageCount == 0)
                .build();

        ListingImage saved = listingImageRepository.save(image);
        return listingMapper.toImageResponse(saved);
    }

    public void deleteImage(UUID listingId, UUID imageId) {
        User currentUser = currentUser();
        Listing listing = findListing(listingId);
        requireOwnerOrAdmin(currentUser, listing);

        ListingImage image = listingImageRepository.findById(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("Image not found", imageId));
        if (!image.getListing().getId().equals(listingId)) {
            throw new ResourceNotFoundException("Image not found", imageId);
        }

        deleteStoredImage(image, listingId);
        listing.removeImage(image);
        listingImageRepository.delete(image);

        if (Boolean.TRUE.equals(image.getIsMain())) {
            listingImageRepository.findByListingIdOrderByOrderIndexAsc(listingId).stream()
                    .findFirst()
                    .ifPresent(first -> {
                        first.setIsMain(true);
                        listingImageRepository.save(first);
                    });
        }
    }

    public void setStatus(Listing listing, ListingStatus status) {
        listing.setStatus(status);
        listingRepository.save(listing);
    }

    public ListingResponse updateStatus(UUID listingId, ListingStatusUpdateRequest request, Locale locale) {
        User currentUser = currentUser();
        Listing listing = findListing(listingId);
        if (!canModify(currentUser, listing)) {
            throw new BusinessException("You are not allowed to change this listing status");
        }
        listing.setStatus(request.status());
        Listing saved = listingRepository.save(listing);
        Profile sellerProfile = findProfile(saved.getSeller().getId());
        return listingMapper.toListingResponse(saved, sellerProfile, locale);
    }

    private Listing findListing(UUID listingId) {
        return listingRepository.findById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing not found", listingId));
    }

    private Profile findProfile(UUID userId) {
        return profileRepository.findByUserId(userId).orElse(null);
    }

    private Map<UUID, Profile> loadProfiles(Collection<Listing> listings) {
        List<UUID> sellerIds = listings.stream()
                .map(l -> l.getSeller().getId())
                .distinct()
                .toList();
        if (sellerIds.isEmpty()) {
            return Map.of();
        }
        return profileRepository.findByUserIdIn(sellerIds).stream()
                .collect(Collectors.toMap(p -> p.getUser().getId(), Function.identity(), (a, b) -> a));
    }

    private Pageable buildPageable(ListingSearchRequest request) {
        Sort.Direction direction;
        try {
            direction = Sort.Direction.fromString(request.getSortDirection());
        } catch (IllegalArgumentException ex) {
            direction = Sort.Direction.DESC;
        }

        String sortBy = request.getSortBy();
        if (!StringUtils.hasText(sortBy) || !List.of("createdAt", "price", "viewsCount", "sellerRating").contains(sortBy)) {
            sortBy = "createdAt";
        }

        Sort sort;
        if ("sellerRating".equals(sortBy)) {
            sort = Sort.by(direction, "seller.profile.rating");
        } else {
            sort = Sort.by(direction, sortBy);
        }
        sort = sort.and(Sort.by(Sort.Direction.DESC, "createdAt"));

        int page = request.getPage() != null && request.getPage() >= 0 ? request.getPage() : 0;
        int size = request.getSize() != null && request.getSize() > 0 ? request.getSize() : 20;
        return PageRequest.of(page, size, sort);
    }

    private void deleteStoredImage(ListingImage image, UUID listingId) {
        String url = image.getUrl();
        if (!StringUtils.hasText(url)) {
            return;
        }
        String expectedPrefix = IMAGES_PREFIX + listingId + "/";
        int index = url.indexOf(expectedPrefix);
        String objectKey = index >= 0
                ? url.substring(index)
                : expectedPrefix + StringUtils.getFilename(url);
        try {
            fileStorageService.delete(IMAGES_BUCKET, objectKey);
        } catch (Exception ex) {
            log.warn("Failed to delete stored image {}: {}", objectKey, ex.getMessage());
        }
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("Image file is required");
        }
        if (!StringUtils.hasText(file.getContentType()) || !file.getContentType().startsWith("image/")) {
            throw new ValidationException("Uploaded file must be an image");
        }
        if (file.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new ValidationException("Image must not exceed 5 MB");
        }
    }

    private String getFileExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex == -1 ? "" : filename.substring(dotIndex);
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

    private void requireRole(User user, Role role) {
        if (user.getRole() != role) {
            throw new BusinessException("Required role: " + role.name());
        }
    }

    private boolean canModify(User user, Listing listing) {
        if (user == null) {
            return false;
        }
        return Objects.equals(user.getId(), listing.getSeller().getId())
                || user.getRole() == Role.ADMIN
                || user.getRole() == Role.MODERATOR;
    }

    private void requireOwnerOrAdmin(User user, Listing listing) {
        if (!canModify(user, listing)) {
            throw new BusinessException("You are not allowed to modify this listing");
        }
    }
}
