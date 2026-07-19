package com.petmarketplace.application.message.service;

import com.petmarketplace.application.message.dto.ConversationResponse;
import com.petmarketplace.application.message.dto.MessageResponse;
import com.petmarketplace.application.message.dto.MessageSendRequest;
import com.petmarketplace.application.message.mapper.MessageMapper;
import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.repository.ListingRepository;
import com.petmarketplace.domain.message.entity.Message;
import com.petmarketplace.domain.message.repository.MessageRepository;
import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.domain.user.repository.UserRepository;
import com.petmarketplace.exception.BusinessException;
import com.petmarketplace.exception.ResourceNotFoundException;
import com.petmarketplace.exception.ValidationException;
import com.petmarketplace.infrastructure.notification.EmailNotificationService;
import com.petmarketplace.infrastructure.notification.PushNotificationService;
import com.petmarketplace.infrastructure.security.UserDetailsImpl;
import com.petmarketplace.infrastructure.storage.FileStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MessageService {

    private static final String MESSAGES_BUCKET = "messages";
    private static final long MAX_ATTACHMENT_SIZE_BYTES = 5L * 1024 * 1024;

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ListingRepository listingRepository;
    private final FileStorageService fileStorageService;
    private final MessageMapper messageMapper;
    private final EmailNotificationService emailNotificationService;
    private final PushNotificationService pushNotificationService;

    public MessageResponse sendMessage(MessageSendRequest request, MultipartFile attachment) {
        return sendMessage(currentUser(), request, attachment);
    }

    public MessageResponse sendMessage(User sender, MessageSendRequest request, MultipartFile attachment) {
        Objects.requireNonNull(sender, "Sender must not be null");
        if (request == null) {
            throw new ValidationException("Message request is required");
        }
        if (request.receiverId() == null) {
            throw new ValidationException("Receiver is required");
        }
        if (Objects.equals(sender.getId(), request.receiverId())) {
            throw new BusinessException("You cannot send a message to yourself");
        }

        User receiver = userRepository.findById(request.receiverId())
                .orElseThrow(() -> new ResourceNotFoundException("Receiver not found", request.receiverId()));

        Listing listing = null;
        if (request.listingId() != null) {
            listing = listingRepository.findById(request.listingId())
                    .orElseThrow(() -> new ResourceNotFoundException("Listing not found", request.listingId()));
        }

        boolean hasAttachment = attachment != null && !attachment.isEmpty();
        if (!StringUtils.hasText(request.content()) && !hasAttachment) {
            throw new ValidationException("Message must contain text or an attachment");
        }

        String attachmentUrl = null;
        if (hasAttachment) {
            attachmentUrl = uploadAttachment(sender, attachment);
        }

        Message message = Message.builder()
                .sender(sender)
                .receiver(receiver)
                .listing(listing)
                .content(request.content())
                .attachmentUrl(attachmentUrl)
                .read(false)
                .build();

        Message saved = messageRepository.save(message);
        log.debug("Sent message {} from {} to {}", saved.getId(), sender.getId(), receiver.getId());

        sendNotification(receiver, saved);

        return messageMapper.toResponse(saved);
    }

    public List<ConversationResponse> getConversations() {
        return getConversations(currentUser());
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> getConversations(User user) {
        Objects.requireNonNull(user, "User must not be null");
        List<Message> lastMessages = messageRepository.findConversationsByUserId(user.getId());
        Map<UUID, Long> unreadMap = messageRepository.countUnreadMessagesByReceiverIdGroupedBySender(user.getId())
                .stream()
                .collect(Collectors.toMap(
                        MessageRepository.UnreadCountProjection::getPartnerId,
                        MessageRepository.UnreadCountProjection::getCount,
                        Long::sum));

        return lastMessages.stream()
                .map(message -> {
                    User partner = resolvePartner(user, message);
                    long unreadCount = unreadMap.getOrDefault(partner.getId(), 0L);
                    return messageMapper.toConversationResponse(message, partner, unreadCount);
                })
                .sorted(Comparator.comparing((ConversationResponse c) -> c.lastMessage().createdAt()).reversed())
                .toList();
    }

    public Page<MessageResponse> getConversation(UUID partnerId, Pageable pageable) {
        return getConversation(currentUser(), partnerId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<MessageResponse> getConversation(User user, UUID partnerId, Pageable pageable) {
        Objects.requireNonNull(user, "User must not be null");
        if (partnerId == null) {
            throw new ValidationException("Partner id is required");
        }
        if (Objects.equals(user.getId(), partnerId)) {
            throw new BusinessException("You cannot view a conversation with yourself");
        }
        return messageRepository.findBySenderIdAndReceiverIdOrReceiverIdAndSenderId(
                        user.getId(), partnerId, pageable)
                .map(messageMapper::toResponse);
    }

    public MessageResponse markAsRead(UUID messageId) {
        return markAsRead(currentUser(), messageId);
    }

    public MessageResponse markAsRead(User user, UUID messageId) {
        Objects.requireNonNull(user, "User must not be null");
        if (messageId == null) {
            throw new ValidationException("Message id is required");
        }
        Message message = messageRepository.findByIdAndReceiverId(messageId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Message not found", messageId));
        message.setRead(true);
        Message saved = messageRepository.save(message);
        log.debug("Marked message {} as read by receiver {}", saved.getId(), user.getId());
        return messageMapper.toResponse(saved);
    }

    public long getUnreadCount() {
        return getUnreadCount(currentUser());
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(User user) {
        return messageRepository.countByReceiverIdAndReadFalse(user.getId());
    }

    private String uploadAttachment(User sender, MultipartFile file) {
        validateAttachment(file);
        String extension = getFileExtension(file.getOriginalFilename());
        String objectKey = "messages/%s/%s%s".formatted(sender.getId(), UUID.randomUUID(), extension);

        String storedUrl;
        try (InputStream inputStream = file.getInputStream()) {
            storedUrl = fileStorageService.store(MESSAGES_BUCKET, objectKey, inputStream,
                    file.getSize(), file.getContentType());
        } catch (IOException ex) {
            throw new BusinessException("Failed to read attachment file");
        }

        if (!StringUtils.hasText(storedUrl)) {
            storedUrl = fileStorageService.getPublicUrl(MESSAGES_BUCKET, objectKey);
        }
        return storedUrl;
    }

    private void validateAttachment(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("Attachment file is required");
        }
        if (!StringUtils.hasText(file.getContentType()) || !file.getContentType().startsWith("image/")) {
            throw new ValidationException("Attachment must be an image");
        }
        if (file.getSize() > MAX_ATTACHMENT_SIZE_BYTES) {
            throw new ValidationException("Attachment must not exceed 5 MB");
        }
    }

    private void sendNotification(User receiver, Message message) {
        User sender = message.getSender();
        try {
            emailNotificationService.sendNewMessageNotification(receiver, sender, message);
        } catch (RuntimeException ex) {
            log.error("Failed to send new-message email notification to {}", receiver.getId(), ex);
        }

        try {
            pushNotificationService.sendNotification(
                    receiver,
                    "New message from " + displayName(sender),
                    message.getContent() != null ? message.getContent() : "You have a new message");
        } catch (RuntimeException ex) {
            log.error("Failed to send new-message push notification to {}", receiver.getId(), ex);
        }
    }

    private String displayName(User user) {
        if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
            String lastName = user.getLastName() != null ? user.getLastName() : "";
            return (user.getFirstName() + " " + lastName).trim();
        }
        return user.getEmail() != null ? user.getEmail() : "User";
    }

    private User resolvePartner(User currentUser, Message message) {
        if (Objects.equals(currentUser.getId(), message.getSender().getId())) {
            return message.getReceiver();
        }
        return message.getSender();
    }

    private String getFileExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex == -1 ? "" : filename.substring(dotIndex);
    }

    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserDetailsImpl details)) {
            throw new BusinessException("User not authenticated");
        }
        return userRepository.findByEmail(details.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
