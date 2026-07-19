package com.petmarketplace.application.user.mapper;

import com.petmarketplace.application.user.dto.ProfileUpdateRequest;
import com.petmarketplace.application.user.dto.PublicProfileResponse;
import com.petmarketplace.application.user.dto.UserProfileResponse;
import com.petmarketplace.domain.user.entity.Profile;
import com.petmarketplace.domain.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ProfileMapper {

    @Mapping(source = "user.id", target = "id")
    @Mapping(source = "user.email", target = "email")
    @Mapping(source = "user.phone", target = "phone")
    @Mapping(source = "user.firstName", target = "firstName")
    @Mapping(source = "user.lastName", target = "lastName")
    @Mapping(source = "user.avatarUrl", target = "avatarUrl")
    @Mapping(source = "user.role", target = "role")
    @Mapping(source = "user.verified", target = "verified")
    @Mapping(source = "user.active", target = "active")
    @Mapping(source = "user.createdAt", target = "createdAt")
    @Mapping(source = "user.updatedAt", target = "updatedAt")
    UserProfileResponse toUserProfileResponse(Profile profile, User user);

    @Mapping(source = "user.id", target = "id")
    @Mapping(source = "user.firstName", target = "firstName")
    @Mapping(source = "user.lastName", target = "lastName")
    @Mapping(source = "user.avatarUrl", target = "avatarUrl")
    @Mapping(source = "user.role", target = "role")
    PublicProfileResponse toPublicProfileResponse(Profile profile, User user);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "rating", ignore = true)
    @Mapping(target = "totalReviews", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateProfileFromRequest(ProfileUpdateRequest request, @MappingTarget Profile profile);
}
