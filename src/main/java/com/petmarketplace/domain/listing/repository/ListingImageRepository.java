package com.petmarketplace.domain.listing.repository;

import com.petmarketplace.domain.listing.entity.ListingImage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ListingImageRepository extends JpaRepository<ListingImage, UUID> {

    List<ListingImage> findByListingIdOrderByOrderIndexAsc(UUID listingId);

    long countByListingId(UUID listingId);

    Optional<ListingImage> findByListingIdAndIsMainTrue(UUID listingId);
}
