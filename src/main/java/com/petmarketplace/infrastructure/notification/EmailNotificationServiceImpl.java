package com.petmarketplace.infrastructure.notification;

import com.petmarketplace.domain.booking.entity.Booking;
import com.petmarketplace.domain.booking.entity.BookingStatus;
import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.message.entity.Message;
import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.infrastructure.notification.mail.BookingStatusMail;
import com.petmarketplace.infrastructure.notification.mail.ListingStatusMail;
import com.petmarketplace.infrastructure.notification.mail.MailDispatcher;
import com.petmarketplace.infrastructure.notification.mail.NewMessageMail;
import com.petmarketplace.infrastructure.notification.mail.PasswordResetMail;
import com.petmarketplace.infrastructure.notification.mail.SubscriptionMatchMail;
import com.petmarketplace.infrastructure.notification.mail.VerificationMail;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Снимает с сущностей всё, что нужно письму, и передаёт снимок {@link MailDispatcher}.
 *
 * <p>Методы намеренно синхронные: они выполняются на потоке вызывающего, внутри его
 * транзакции, — единственном месте, где ленивые связи можно безопасно инициализировать.
 * Асинхронна только отправка, и она сущностей уже не видит. Раньше {@code @Async} висел
 * прямо здесь, и ленивый {@code User} из подписки инициализировался на потоке пула в чужой
 * сессии Hibernate: сессия ломалась, а транзакция вызывающего падала на коммите.
 *
 * <p>Поэтому не возвращайте сюда {@code @Async} и не передавайте сущности дальше в
 * dispatcher — весь смысл разделения в этом.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailNotificationServiceImpl implements EmailNotificationService {

    private final MailDispatcher dispatcher;

    @Override
    public void sendVerificationEmail(User user, String token) {
        Objects.requireNonNull(user, "User must not be null");
        Objects.requireNonNull(token, "Token must not be null");

        dispatcher.sendVerification(
                new VerificationMail(user.getEmail(), displayName(user), token));
    }

    @Override
    public void sendPasswordResetEmail(User user, String token) {
        Objects.requireNonNull(user, "User must not be null");
        Objects.requireNonNull(token, "Token must not be null");

        dispatcher.sendPasswordReset(
                new PasswordResetMail(user.getEmail(), displayName(user), token));
    }

    @Override
    public void sendNewMessageNotification(User receiver, User sender, Message message) {
        Objects.requireNonNull(receiver, "Receiver must not be null");
        Objects.requireNonNull(sender, "Sender must not be null");
        Objects.requireNonNull(message, "Message must not be null");

        dispatcher.sendNewMessage(new NewMessageMail(
                receiver.getEmail(),
                displayName(receiver),
                displayName(sender),
                sender.getId(),
                message.getContent(),
                message.getAttachmentUrl() != null));
    }

    @Override
    public void sendListingStatusUpdate(User seller, Listing listing, ListingStatus status) {
        Objects.requireNonNull(seller, "Seller must not be null");
        Objects.requireNonNull(listing, "Listing must not be null");
        Objects.requireNonNull(status, "Status must not be null");

        dispatcher.sendListingStatus(new ListingStatusMail(
                seller.getEmail(),
                displayName(seller),
                listing.getId(),
                listing.getTitle(),
                status));
    }

    @Override
    public void sendBookingStatusUpdate(User participant, Booking booking, BookingStatus status) {
        Objects.requireNonNull(participant, "Participant must not be null");
        Objects.requireNonNull(booking, "Booking must not be null");
        Objects.requireNonNull(status, "Status must not be null");

        boolean isBuyer = booking.getBuyer() != null
                && Objects.equals(booking.getBuyer().getId(), participant.getId());
        User otherParty = isBuyer ? booking.getSeller() : booking.getBuyer();

        dispatcher.sendBookingStatus(new BookingStatusMail(
                participant.getEmail(),
                displayName(participant),
                otherParty != null ? displayName(otherParty) : "",
                booking.getId(),
                // Обход booking.listing раньше происходил в шаблоне, то есть уже на потоке
                // пула, — здесь связь разворачивается внутри транзакции вызывающего.
                booking.getListing() != null ? booking.getListing().getTitle() : "",
                status,
                isBuyer));
    }

    @Override
    public void sendSubscriptionMatch(User user, Listing listing) {
        Objects.requireNonNull(user, "User must not be null");
        Objects.requireNonNull(listing, "Listing must not be null");

        dispatcher.sendSubscriptionMatch(new SubscriptionMatchMail(
                user.getEmail(),
                displayName(user),
                listing.getId(),
                listing.getTitle(),
                listing.getPrice(),
                listing.getCurrency(),
                listing.getLocationCity()));
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
}
