package com.petmarketplace.domain.booking.repository;

import com.petmarketplace.domain.booking.entity.Booking;
import com.petmarketplace.domain.booking.entity.BookingStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Page<Booking> findByBuyerId(UUID buyerId, Pageable pageable);

    Page<Booking> findBySellerId(UUID sellerId, Pageable pageable);

    Page<Booking> findByListingId(UUID listingId, Pageable pageable);

    Page<Booking> findByBuyerIdOrSellerId(UUID buyerId, UUID sellerId, Pageable pageable);

    long countByStatus(BookingStatus status);

    boolean existsByListingIdAndBuyerIdAndStatusIn(UUID listingId, UUID buyerId, Collection<BookingStatus> statuses);

    @Query("""
            select b from Booking b
            left join fetch b.listing
            left join fetch b.buyer buyer
            left join fetch buyer.profile
            left join fetch b.seller seller
            left join fetch seller.profile
            where b.id = :id
            """)
    Optional<Booking> findByIdWithDetails(@Param("id") UUID id);
}
