package com.petmarketplace.infrastructure.notification.mail;

import com.petmarketplace.infrastructure.mail.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * Рендерит и отправляет письма на пуле {@code emailTaskExecutor}.
 *
 * <p>Каждый метод принимает неизменяемый снимок, а не сущность JPA, и это главное в классе.
 * Раньше письма собирались прямо из сущностей на потоке пула, а сущность приходила из ещё
 * открытой транзакции вызывающего потока: инициализация ленивого прокси на чужом потоке шла
 * в чужую сессию Hibernate и рушила её состояние — вызывающая транзакция падала на коммите
 * с {@code ConcurrentModificationException}. Сигнатуры со снимками делают тот сценарий
 * невыразимым: чтобы позвать любой метод отсюда, все поля нужно вычитать заранее.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailDispatcher {

    private static final String TEMPLATE_DIR = "mail/";

    private final EmailSender emailSender;
    private final SpringTemplateEngine templateEngine;

    @Value("${app.mail.base-url:http://localhost:8080/api/v1}")
    private String baseUrl;

    @Async("emailTaskExecutor")
    public void sendVerification(VerificationMail mail) {
        Context context = new Context();
        context.setVariable("displayName", mail.displayName());
        context.setVariable("token", mail.token());
        context.setVariable("verificationLink", baseUrl + "/auth/verify-email?token=" + mail.token());

        emailSender.sendHtml(mail.recipientEmail(), "Verify your PetMarketplace account",
                render("verification", context));
        log.debug("Sent verification email to {}", mail.recipientEmail());
    }

    @Async("emailTaskExecutor")
    public void sendPasswordReset(PasswordResetMail mail) {
        Context context = new Context();
        context.setVariable("displayName", mail.displayName());
        context.setVariable("token", mail.token());
        context.setVariable("resetLink", baseUrl + "/auth/reset-password?token=" + mail.token());

        emailSender.sendHtml(mail.recipientEmail(), "Reset your PetMarketplace password",
                render("password-reset", context));
        log.debug("Sent password reset email to {}", mail.recipientEmail());
    }

    @Async("emailTaskExecutor")
    public void sendNewMessage(NewMessageMail mail) {
        Context context = new Context();
        context.setVariable("receiverName", mail.receiverName());
        context.setVariable("senderName", mail.senderName());
        context.setVariable("messageContent", mail.messageContent());
        context.setVariable("hasAttachment", mail.hasAttachment());
        context.setVariable("conversationLink", baseUrl + "/messages/" + mail.senderId());

        emailSender.sendHtml(mail.recipientEmail(), "You have a new message on PetMarketplace",
                render("new-message", context));
        log.debug("Sent new-message notification to {}", mail.recipientEmail());
    }

    @Async("emailTaskExecutor")
    public void sendListingStatus(ListingStatusMail mail) {
        Context context = new Context();
        context.setVariable("sellerName", mail.sellerName());
        context.setVariable("listingTitle", mail.listingTitle());
        context.setVariable("status", mail.status());
        context.setVariable("statusLabel", formatStatus(mail.status().name()));
        context.setVariable("listingLink", baseUrl + "/listings/" + mail.listingId());

        emailSender.sendHtml(mail.recipientEmail(), "Your listing status was updated",
                render("listing-status", context));
        log.debug("Sent listing-status update to {} for listing {}",
                mail.recipientEmail(), mail.listingId());
    }

    @Async("emailTaskExecutor")
    public void sendBookingStatus(BookingStatusMail mail) {
        Context context = new Context();
        context.setVariable("participantName", mail.participantName());
        context.setVariable("otherPartyName", mail.otherPartyName());
        context.setVariable("listingTitle", mail.listingTitle());
        context.setVariable("status", mail.status());
        context.setVariable("statusLabel", formatStatus(mail.status().name()));
        context.setVariable("isBuyer", mail.buyer());
        context.setVariable("bookingLink", baseUrl + "/bookings/" + mail.bookingId());

        emailSender.sendHtml(mail.recipientEmail(),
                "Booking update: " + formatStatus(mail.status().name()),
                render("booking-status", context));
        log.debug("Sent booking-status update to {} ({})",
                mail.recipientEmail(), mail.buyer() ? "buyer" : "seller");
    }

    @Async("emailTaskExecutor")
    public void sendSubscriptionMatch(SubscriptionMatchMail mail) {
        Context context = new Context();
        context.setVariable("userName", mail.userName());
        context.setVariable("listingTitle", mail.listingTitle());
        context.setVariable("listingPrice", mail.listingPrice());
        context.setVariable("listingCurrency", mail.listingCurrency());
        context.setVariable("listingCity", mail.listingCity());
        context.setVariable("listingLink", baseUrl + "/listings/" + mail.listingId());

        emailSender.sendHtml(mail.recipientEmail(), "New matching listing on PetMarketplace",
                render("subscription-match", context));
        log.debug("Sent subscription match to {} for listing {}",
                mail.recipientEmail(), mail.listingId());
    }

    private String render(String templateName, Context context) {
        return templateEngine.process(TEMPLATE_DIR + templateName, context);
    }

    private String formatStatus(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        return status.replace('_', ' ').toLowerCase();
    }
}
