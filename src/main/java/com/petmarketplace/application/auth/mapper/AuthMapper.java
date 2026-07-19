package com.petmarketplace.application.auth.mapper;

import com.petmarketplace.application.auth.dto.RegisterRequest;
import com.petmarketplace.domain.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AuthMapper {

    User toUser(RegisterRequest request);
}
