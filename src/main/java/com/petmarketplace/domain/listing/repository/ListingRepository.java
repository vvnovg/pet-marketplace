package com.petmarketplace.domain.listing.repository;

import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ListingRepository extends JpaRepository<Listing, UUID>, JpaSpecificationExecutor<Listing> {

    Page<Listing> findBySellerId(UUID sellerId, Pageable pageable);

    long countByStatus(ListingStatus status);

    Page<Listing> findByStatusIn(Collection<ListingStatus> statuses, Pageable pageable);

    @Query("""
            select count(l) from Listing l
            where l.createdAt >= :since
            """)
    long countCreatedSince(@Param("since") Instant since);

    @Query("""
            select l from Listing l
            left join fetch l.seller s
            left join fetch s.profile
            left join fetch l.category
            left join fetch l.breed
            left join fetch l.images
            where l.id = :id
            """)
    Optional<Listing> findByIdWithSeller(@Param("id") UUID id);

    default Page<Listing> findActiveByFilters(Specification<Listing> filters, Pageable pageable) {
        Specification<Listing> activeSpec = (root, query, cb) -> cb.equal(root.get("status"), ListingStatus.ACTIVE);
        return findAll(activeSpec.and(filters), pageable);
    }
}
