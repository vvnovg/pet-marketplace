package com.petmarketplace.domain.favorite.repository;

import com.petmarketplace.domain.favorite.entity.Favorite;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface FavoriteRepository extends JpaRepository<Favorite, UUID> {

    List<Favorite> findByUserId(UUID userId);

    boolean existsByUserIdAndListingId(UUID userId, UUID listingId);

    void deleteByUserIdAndListingId(UUID userId, UUID listingId);

    Optional<Favorite> findByUserIdAndListingId(UUID userId, UUID listingId);

    @Query("""
            select distinct f from Favorite f
            join fetch f.listing l
            left join fetch l.images
            where f.user.id = :userId
            order by f.createdAt desc
            """)
    List<Favorite> findByUserIdWithListings(@Param("userId") UUID userId);
}
