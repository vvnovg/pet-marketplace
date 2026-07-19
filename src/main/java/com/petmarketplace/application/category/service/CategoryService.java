package com.petmarketplace.application.category.service;

import com.petmarketplace.application.category.dto.CategoryResponse;
import com.petmarketplace.application.category.dto.CategoryWithBreedsResponse;
import com.petmarketplace.application.category.mapper.CategoryMapper;
import com.petmarketplace.domain.category.entity.Category;
import com.petmarketplace.domain.category.repository.CategoryRepository;
import com.petmarketplace.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    @Cacheable(value = "categories", key = "'roots:' + #locale.toLanguageTag()")
    public List<CategoryResponse> getAllRootCategories(Locale locale) {
        return categoryRepository.findByParentIsNull().stream()
                .map(category -> categoryMapper.toCategoryResponse(category, locale))
                .toList();
    }

    @Cacheable(value = "categories", key = "'slug:' + #slug + ':' + #locale.toLanguageTag()")
    public CategoryResponse getCategoryBySlug(String slug, Locale locale) {
        Category category = categoryRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + slug));
        return categoryMapper.toCategoryResponse(category, locale);
    }

    @Cacheable(value = "breeds", key = "'category-id:' + #categoryId + ':' + #locale.toLanguageTag()")
    public CategoryWithBreedsResponse getBreedsByCategoryId(UUID categoryId, Locale locale) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryId));
        return categoryMapper.toCategoryWithBreedsResponse(category, locale);
    }

    @Cacheable(value = "breeds", key = "'category-slug:' + #slug + ':' + #locale.toLanguageTag()")
    public CategoryWithBreedsResponse getBreedsByCategorySlug(String slug, Locale locale) {
        Category category = categoryRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + slug));
        return categoryMapper.toCategoryWithBreedsResponse(category, locale);
    }
}
