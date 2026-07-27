package com.petmarketplace.application.user.service;

import com.petmarketplace.application.review.service.ReviewService;
import com.petmarketplace.application.user.dto.ProfileUpdateRequest;
import com.petmarketplace.application.user.dto.PublicProfileResponse;
import com.petmarketplace.application.user.dto.UserProfileResponse;
import com.petmarketplace.application.user.mapper.ProfileMapper;
import com.petmarketplace.domain.user.entity.Profile;
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
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class ProfileService {

    private static final String AVATAR_BUCKET = "avatars";
    private static final String AVATAR_PREFIX = "avatars/";
    private static final long MAX_AVATAR_SIZE_BYTES = 5L * 1024 * 1024;

    private final ProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final ProfileMapper profileMapper;
    private final ReviewService reviewService;

    public UserProfileResponse getCurrentProfile() {
        User user = currentUser();
        Profile profile = findOrCreateProfile(user);
        return profileMapper.toUserProfileResponse(profile, user);
    }

    public UserProfileResponse updateCurrentProfile(ProfileUpdateRequest request) {
        User user = currentUser();
        Profile profile = findOrCreateProfile(user);
        profileMapper.updateProfileFromRequest(request, profile);
        // firstName/lastName/phone живут в User, а не в Profile, поэтому маппер их
        // не трогает — присваиваем явно. Семантика PUT: null затирает поле.
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setPhone(request.phone());
        userRepository.save(user);
        return profileMapper.toUserProfileResponse(profileRepository.save(profile), user);
    }

    @Transactional(readOnly = true)
    public PublicProfileResponse getPublicProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Profile profile = findProfileOrEmpty(user);
        return profileMapper.toPublicProfileResponse(profile, user);
    }

    public UserProfileResponse uploadAvatar(MultipartFile file) {
        validateAvatar(file);
        User user = currentUser();

        String previousUrl = user.getAvatarUrl();
        String extension = getFileExtension(file.getOriginalFilename());
        String objectKey = "%s%s/%s%s".formatted(AVATAR_PREFIX, user.getId(), UUID.randomUUID(), extension);

        String storedUrl;
        try (InputStream inputStream = file.getInputStream()) {
            storedUrl = fileStorageService.store(AVATAR_BUCKET, objectKey, inputStream,
                    file.getSize(), file.getContentType());
        } catch (IOException ex) {
            throw new BusinessException("Failed to read avatar file");
        }

        if (!StringUtils.hasText(storedUrl)) {
            storedUrl = fileStorageService.getPublicUrl(AVATAR_BUCKET, objectKey);
        }

        user.setAvatarUrl(storedUrl);
        userRepository.save(user);

        // Only after the new URL is persisted: a failed delete must never cost the user the
        // avatar they just uploaded. Keys are immutable and publicly readable, so the previous
        // object would otherwise stay reachable forever and grow the volume without bound.
        deleteStoredAvatar(previousUrl, user.getId());

        Profile profile = findOrCreateProfile(user);
        return profileMapper.toUserProfileResponse(profile, user);
    }

    private void deleteStoredAvatar(String previousUrl, UUID userId) {
        if (!StringUtils.hasText(previousUrl)) {
            return;
        }
        String expectedPrefix = AVATAR_PREFIX + userId + "/";
        int index = previousUrl.indexOf(expectedPrefix);
        if (index < 0) {
            // Not an object this service stored (an externally hosted or seeded URL) — deleting
            // a guessed key here could remove something that isn't ours.
            return;
        }
        String objectKey = previousUrl.substring(index);
        try {
            fileStorageService.delete(AVATAR_BUCKET, objectKey);
        } catch (Exception ex) {
            log.warn("Failed to delete previous avatar {}: {}", objectKey, ex.getMessage());
        }
    }

    public Profile createEmptyProfile(User user) {
        if (profileRepository.existsByUserId(user.getId())) {
            return profileRepository.findByUserId(user.getId())
                    .orElseThrow(() -> new BusinessException("Profile inconsistency for user " + user.getId()));
        }

        Profile profile = Profile.builder()
                .user(user)
                .rating(BigDecimal.ZERO)
                .totalReviews(0)
                .build();
        return profileRepository.save(profile);
    }

    public void recalculateRating(UUID userId) {
        reviewService.recalculateRating(userId);
    }

    private Profile findOrCreateProfile(User user) {
        return profileRepository.findByUserId(user.getId())
                .orElseGet(() -> createEmptyProfile(user));
    }

    private Profile findProfileOrEmpty(User user) {
        return profileRepository.findByUserId(user.getId())
                .orElseGet(() -> Profile.builder()
                        .user(user)
                        .rating(BigDecimal.ZERO)
                        .totalReviews(0)
                        .build());
    }

    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserDetailsImpl details)) {
            throw new BusinessException("User not authenticated");
        }
        return userRepository.findByEmail(details.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private void validateAvatar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("Avatar file is required");
        }
        if (!StringUtils.hasText(file.getContentType()) || !file.getContentType().startsWith("image/")) {
            throw new ValidationException("Avatar must be an image");
        }
        if (file.getSize() > MAX_AVATAR_SIZE_BYTES) {
            throw new ValidationException("Avatar must not exceed 5 MB");
        }
    }

    private String getFileExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex == -1 ? "" : filename.substring(dotIndex);
    }
}
