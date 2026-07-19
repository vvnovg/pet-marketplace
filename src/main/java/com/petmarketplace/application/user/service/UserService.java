package com.petmarketplace.application.user.service;

import com.petmarketplace.application.user.dto.PublicProfileResponse;
import com.petmarketplace.application.user.dto.UserProfileResponse;
import com.petmarketplace.domain.user.entity.Role;
import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.domain.user.repository.UserRepository;
import com.petmarketplace.exception.ResourceNotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final ProfileService profileService;

    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUser() {
        return profileService.getCurrentProfile();
    }

    @Transactional(readOnly = true)
    public PublicProfileResponse getPublicUser(UUID userId) {
        return profileService.getPublicProfile(userId);
    }

    public void changeRole(UUID userId, Role role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setRole(role);
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public Page<Object> listUserListings(UUID userId, Pageable pageable) {
        // TODO: delegate to ListingRepository once the listings module is implemented.
        return Page.empty(pageable);
    }
}
