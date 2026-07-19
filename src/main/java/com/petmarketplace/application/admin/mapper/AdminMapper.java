package com.petmarketplace.application.admin.mapper;

import com.petmarketplace.application.admin.dto.AdminUserResponse;
import com.petmarketplace.domain.user.entity.Profile;
import com.petmarketplace.domain.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AdminMapper {

    @Mapping(source = "user.id", target = "id")
    @Mapping(source = "user.email", target = "email")
    @Mapping(source = "user.phone", target = "phone")
    @Mapping(source = "user.firstName", target = "firstName")
    @Mapping(source = "user.lastName", target = "lastName")
    @Mapping(source = "user.avatarUrl", target = "avatarUrl")
    @Mapping(source = "user.role", target = "role")
    @Mapping(source = "user.verified", target = "verified")
    @Mapping(source = "user.active", target = "active")
    @Mapping(source = "profile.bio", target = "bio")
    @Mapping(source = "profile.country", target = "country")
    @Mapping(source = "profile.city", target = "city")
    @Mapping(source = "profile.address", target = "address")
    @Mapping(source = "profile.latitude", target = "latitude")
    @Mapping(source = "profile.longitude", target = "longitude")
    @Mapping(source = "profile.rating", target = "rating")
    @Mapping(source = "profile.totalReviews", target = "totalReviews")
    @Mapping(source = "user.createdAt", target = "createdAt")
    @Mapping(source = "user.updatedAt", target = "updatedAt")
    AdminUserResponse toAdminUserResponse(Profile profile, User user);
}
