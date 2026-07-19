package com.petmarketplace.domain.message.repository;

import com.petmarketplace.domain.message.entity.Message;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    /**
     * Returns the latest message for each conversation partner of the given user.
     * <p>
     * Formulated with a correlated {@code NOT EXISTS} rather than PostgreSQL {@code DISTINCT ON}:
     * Hibernate's native-query parser empties the {@code DISTINCT ON (...)} list when it contains a
     * named parameter (the CASE over :userId), producing "SELECT DISTINCT ON ( ORDER BY ...)" and a
     * 500. Keeping :userId only in WHERE / equality operands avoids that parser quirk.
     */
    @Query(value = """
            SELECT m.id, m.sender_id, m.receiver_id, m.listing_id, m.content,
                   m.attachment_url, m.is_read, m.created_at
            FROM messages m
            WHERE (m.sender_id = :userId OR m.receiver_id = :userId)
              AND NOT EXISTS (
                  SELECT 1 FROM messages m2
                  WHERE (m2.sender_id = :userId OR m2.receiver_id = :userId)
                    AND (CASE WHEN m2.sender_id = :userId THEN m2.receiver_id ELSE m2.sender_id END)
                        = (CASE WHEN m.sender_id  = :userId THEN m.receiver_id  ELSE m.sender_id  END)
                    AND (m2.created_at, m2.id) > (m.created_at, m.id)
              )
            ORDER BY m.created_at DESC, m.id DESC
            """, nativeQuery = true)
    List<Message> findConversationsByUserId(@Param("userId") UUID userId);

    @Query("""
            select m from Message m
            where (m.sender.id = :user1 and m.receiver.id = :user2)
               or (m.sender.id = :user2 and m.receiver.id = :user1)
            order by m.createdAt desc, m.id desc
            """)
    Page<Message> findBySenderIdAndReceiverIdOrReceiverIdAndSenderId(
            @Param("user1") UUID user1,
            @Param("user2") UUID user2,
            Pageable pageable);

    long countByReceiverIdAndReadFalse(UUID receiverId);

    Optional<Message> findByIdAndReceiverId(UUID id, UUID receiverId);

    @Query("""
            select m.sender.id as partnerId, count(m) as count
            from Message m
            where m.receiver.id = :userId and m.read = false
            group by m.sender.id
            """)
    List<UnreadCountProjection> countUnreadMessagesByReceiverIdGroupedBySender(@Param("userId") UUID userId);

    interface UnreadCountProjection {

        UUID getPartnerId();

        Long getCount();
    }
}
