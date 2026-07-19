package com.petmarketplace.domain.category.repository;

import com.petmarketplace.domain.category.entity.Breed;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BreedRepository extends JpaRepository<Breed, UUID> {

    List<Breed> findByCategoryId(UUID categoryId);

    List<Breed> findByCategorySlug(String slug);
}
