package com.petmarketplace.infrastructure.notification;

import com.petmarketplace.domain.booking.entity.Booking;
import com.petmarketplace.domain.booking.entity.BookingStatus;
import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.message.entity.Message;
import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.infrastructure.mail.EmailSender;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailNotificationServiceImpl implements EmailNotificationService {

    private static final String TEMPLATE_DIR = "mail/";

    private final EmailSender emailSender;
    private final SpringTemplateEngine templateEngine;

    @Value("${app.mail.base-url:http://localhost:8080/api/v1}")
    private String baseUrl;

    @Async("emailTaskExecutor")
    @Override
    public void sendVerificationEmail(User user, String token) {
        Objects.requireNonNull(user, "User must not be null");
        Objects.requireNonNull(token, "Token must not be null");

        Context context = new Context();
        context.setVariable("user", user);
        context.setVariable("token", token);
        context.setVariable("verificationLink", baseUrl + "/auth/verify-email?token=" + token);
        context.setVariable("displayName", displayName(user));

        String html = processTemplate("verification", context);
        emailSender.sendHtml(user.getEmail(), "Verify your PetMarketplace account", html);
        log.debug("Sent verification email to {}", user.getEmail());
    }

    @Async("emailTaskExecutor")
    @Override
    public void sendPasswordResetEmail(User user, String token) {
        Objects.requireNonNull(user, "User must not be null");
        Objects.requireNonNull(token, "Token must not be null");

        Context context = new Context();
        context.setVariable("user", user);
        context.setVariable("token", token);
        context.setVariable("resetLink", baseUrl + "/auth/reset-password?token=" + token);
        context.setVariable("displayName", displayName(user));

        String html = processTemplate("password-reset", context);
        emailSender.sendHtml(user.getEmail(), "Reset your PetMarketplace password", html);
        log.debug("Sent password reset email to {}", user.getEmail());
    }

    @Async("emailTaskExecutor")
    @Override
    public void sendNewMessageNotification(User receiver, User sender, Message message) {
        Objects.requireNonNull(receiver, "Receiver must not be null");
        Objects.requireNonNull(sender, "Sender must not be null");
        Objects.requireNonNull(message, "Message must not be null");

        Context context = new Context();
        context.setVariable("receiver", receiver);
        context.setVariable("sender", sender);
        context.setVariable("message", message);
        context.setVariable("conversationLink", baseUrl + "/messages/" + sender.getId());
        context.setVariable("senderName", displayName(sender));
        context.setVariable("receiverName", displayName(receiver));
        context.setVariable("hasAttachment", message.getAttachmentUrl() != null);

        String html = processTemplate("new-message", context);
        emailSender.sendHtml(receiver.getEmail(), "You have a new message on PetMarketplace", html);
        log.debug("Sent new-message notification to {}", receiver.getEmail());
    }

    @Async("emailTaskExecutor")
    @Override
    public void sendListingStatusUpdate(User seller, Listing listing, ListingStatus status) {
        Objects.requireNonNull(seller, "Seller must not be null");
        Objects.requireNonNull(listing, "Listing must not be null");
        Objects.requireNonNull(status, "Status must not be null");

        Context context = new Context();
        context.setVariable("seller", seller);
        context.setVariable("listing", listing);
        context.setVariable("status", status);
        context.setVariable("listingLink", baseUrl + "/listings/" + listing.getId());
        context.setVariable("sellerName", displayName(seller));
        context.setVariable("statusLabel", formatStatus(status.name()));

        String html = processTemplate("listing-status", context);
        emailSender.sendHtml(seller.getEmail(), "Your listing status was updated", html);
        log.debug("Sent listing-status update to {} for listing {}", seller.getEmail(), listing.getId());
    }

    @Async("emailTaskExecutor")
    @Override
    public void sendBookingStatusUpdate(User participant, Booking booking, BookingStatus status) {
        Objects.requireNonNull(participant, "Participant must not be null");
        Objects.requireNonNull(booking, "Booking must not be null");
        Objects.requireNonNull(status, "Status must not be null");

        boolean isBuyer = Objects.equals(participant.getId(), booking.getBuyer().getId());
        User otherParty = isBuyer ? booking.getSeller() : booking.getBuyer();

        Context context = new Context();
        context.setVariable("participant", participant);
        context.setVariable("booking", booking);
        context.setVariable("status", status);
        context.setVariable("otherParty", otherParty);
        context.setVariable("isBuyer", isBuyer);
        context.setVariable("participantName", displayName(participant));
        context.setVariable("otherPartyName", displayName(otherParty));
        context.setVariable("bookingLink", baseUrl + "/bookings/" + booking.getId());
        context.setVariable("statusLabel", formatStatus(status.name()));

        String html = processTemplate("booking-status", context);
        String role = isBuyer ? "buyer" : "seller";
        emailSender.sendHtml(participant.getEmail(), "Booking update: " + formatStatus(status.name()), html);
        log.debug("Sent booking-status update to {} ({})", participant.getEmail(), role);
    }

    @Async("emailTaskExecutor")
    @Override
    public void sendSubscriptionMatch(User user, Listing listing) {
        Objects.requireNonNull(user, "User must not be null");
        Objects.requireNonNull(listing, "Listing must not be null");

        Context context = new Context();
        context.setVariable("user", user);
        context.setVariable("listing", listing);
        context.setVariable("listingLink", baseUrl + "/listings/" + listing.getId());
        context.setVariable("userName", displayName(user));

        String html = processTemplate("subscription-match", context);
        emailSender.sendHtml(user.getEmail(), "New matching listing on PetMarketplace", html);
        log.debug("Sent subscription match to {} for listing {}", user.getEmail(), listing.getId());
    }

    private String processTemplate(String templateName, Context context) {
        return templateEngine.process(TEMPLATE_DIR + templateName, context);
    }

    private String displayName(User user) {
        if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
            String lastName = user.getLastName() != null ? user.getLastName() : "";
            return (user.getFirstName() + " " + lastName).trim();
        }
        if (user.getEmail() != null && user.getEmail().contains("@")) {
            return user.getEmail().substring(0, user.getEmail().indexOf('@'));
        }
        return "User";
    }

    private String formatStatus(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        return status.replace('_', ' ').toLowerCase();
    }
}
