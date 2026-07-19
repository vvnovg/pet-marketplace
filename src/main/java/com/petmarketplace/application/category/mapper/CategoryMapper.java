package com.petmarketplace.application.category.mapper;

import com.petmarketplace.application.category.dto.BreedResponse;
import com.petmarketplace.application.category.dto.CategoryResponse;
import com.petmarketplace.application.category.dto.CategoryWithBreedsResponse;
import com.petmarketplace.domain.category.entity.Breed;
import com.petmarketplace.domain.category.entity.Category;
import com.petmarketplace.infrastructure.localization.LocalizedNameResolver;
import java.util.Locale;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CategoryMapper {

    @Mapping(
            target = "name",
            expression = "java(com.petmarketplace.infrastructure.localization.LocalizedNameResolver.resolve(category.getNameRu(), category.getNameEn(), locale))"
    )
    CategoryResponse toCategoryResponse(Category category, @Context Locale locale);

    @Mapping(
            target = "name",
            expression = "java(com.petmarketplace.infrastructure.localization.LocalizedNameResolver.resolve(breed.getNameRu(), breed.getNameEn(), locale))"
    )
    BreedResponse toBreedResponse(Breed breed, @Context Locale locale);

    @Mapping(
            target = "name",
            expression = "java(com.petmarketplace.infrastructure.localization.LocalizedNameResolver.resolve(category.getNameRu(), category.getNameEn(), locale))"
    )
    CategoryWithBreedsResponse toCategoryWithBreedsResponse(Category category, @Context Locale locale);
}
