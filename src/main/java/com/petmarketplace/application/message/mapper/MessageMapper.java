package com.petmarketplace.application.message.mapper;

import com.petmarketplace.application.message.dto.ConversationResponse;
import com.petmarketplace.application.message.dto.MessageListingResponse;
import com.petmarketplace.application.message.dto.MessageResponse;
import com.petmarketplace.application.user.dto.PublicProfileResponse;
import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingImage;
import com.petmarketplace.domain.message.entity.Message;
import com.petmarketplace.domain.user.entity.Profile;
import com.petmarketplace.domain.user.entity.User;
import java.util.Comparator;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class MessageMapper {

    public MessageResponse toResponse(Message message) {
        if (message == null) {
            return null;
        }
        return new MessageResponse(
                message.getId(),
                mapUser(message.getSender()),
                mapUser(message.getReceiver()),
                mapListing(message.getListing()),
                message.getContent(),
                message.getAttachmentUrl(),
                message.isRead(),
                message.getCreatedAt()
        );
    }

    public ConversationResponse toConversationResponse(Message lastMessage, User partner, long unreadCount) {
        if (lastMessage == null) {
            return null;
        }
        return new ConversationResponse(
                mapUser(partner),
                toResponse(lastMessage),
                unreadCount
        );
    }

    private PublicProfileResponse mapUser(User user) {
        if (user == null) {
            return null;
        }
        Profile profile = user.getProfile();
        return new PublicProfileResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getAvatarUrl(),
                profile != null ? profile.getBio() : null,
                profile != null ? profile.getCountry() : null,
                profile != null ? profile.getCity() : null,
                profile != null ? profile.getRating() : null,
                profile != null ? profile.getTotalReviews() : null,
                user.getRole()
        );
    }

    private MessageListingResponse mapListing(Listing listing) {
        if (listing == null) {
            return null;
        }
        return new MessageListingResponse(
                listing.getId(),
                listing.getTitle(),
                resolveMainImageUrl(listing)
        );
    }

    private String resolveMainImageUrl(Listing listing) {
        if (listing.getImages() == null || listing.getImages().isEmpty()) {
            return null;
        }
        return listing.getImages().stream()
                .filter(image -> Boolean.TRUE.equals(image.getIsMain()))
                .min(Comparator.comparingInt(ListingImage::getOrderIndex))
                .map(ListingImage::getUrl)
                .orElseGet(() -> listing.getImages().stream()
                        .min(Comparator.comparingInt(ListingImage::getOrderIndex))
                        .map(ListingImage::getUrl)
                        .orElse(null));
    }

    public boolean isPartner(User currentUser, Message message, User candidate) {
        if (currentUser == null || message == null || candidate == null) {
            return false;
        }
        User partner = Objects.equals(currentUser.getId(), message.getSender().getId())
                ? message.getReceiver()
                : message.getSender();
        return Objects.equals(partner.getId(), candidate.getId());
    }
}
