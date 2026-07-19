package com.petmarketplace.domain.review.repository;

import com.petmarketplace.domain.review.entity.Review;
import com.petmarketplace.domain.review.entity.ReviewStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Page<Review> findByRecipientId(UUID recipientId, Pageable pageable);

    Page<Review> findByRecipientIdAndStatus(UUID recipientId, ReviewStatus status, Pageable pageable);

    Page<Review> findByAuthorId(UUID authorId, Pageable pageable);

    boolean existsByBookingId(UUID bookingId);

    Page<Review> findByStatus(ReviewStatus status, Pageable pageable);

    long countByStatus(ReviewStatus status);

    @Query("""
            select coalesce(avg(r.rating), 0.0) from Review r
            where r.recipient.id = :recipientId and r.status = :status
            """)
    Double calculateAverageRatingByRecipientIdAndStatus(
            @Param("recipientId") UUID recipientId,
            @Param("status") ReviewStatus status);

    @Query("""
            select count(r) from Review r
            where r.recipient.id = :recipientId and r.status = :status
            """)
    Long countByRecipientIdAndStatus(
            @Param("recipientId") UUID recipientId,
            @Param("status") ReviewStatus status);
}
