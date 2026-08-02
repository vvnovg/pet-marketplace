package com.petmarketplace.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petmarketplace.domain.booking.entity.Booking;
import com.petmarketplace.domain.booking.entity.BookingStatus;
import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.infrastructure.notification.mail.BookingStatusMail;
import com.petmarketplace.infrastructure.notification.mail.MailDispatcher;
import com.petmarketplace.infrastructure.notification.mail.SubscriptionMatchMail;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Async;

/**
 * Письма собираются из сущностей JPA, а отправляются на отдельном пуле. Тесты закрепляют
 * границу между этими двумя шагами: сборка обязана целиком произойти на потоке вызывающего,
 * внутри его транзакции. Когда {@code @Async} стоял на самой сборке, ленивый {@code User} из
 * подписки инициализировался на потоке пула в сессии Hibernate вызывающего потока и рушил её —
 * модерация объявления падала на коммите с {@code ConcurrentModificationException}.
 */
class EmailNotificationServiceImplTest {

    private final MailDispatcher dispatcher = mock(MailDispatcher.class);
    private final EmailNotificationServiceImpl service = new EmailNotificationServiceImpl(dispatcher);

    /**
     * Собственно защита от повторения бага: асинхронной может быть только отправка. Стоит
     * кому-то вернуть {@code @Async} на сборку — сущности снова поедут на чужой поток.
     */
    @Test
    void onlyTheDispatcherMayBeAsynchronous() {
        assertThat(Arrays.stream(EmailNotificationServiceImpl.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Async.class)))
                .as("сборка письма обязана идти на потоке вызывающего, внутри его транзакции")
                .isEmpty();

        assertThat(Arrays.stream(MailDispatcher.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("send"))
                .allMatch(this::isAsync))
                .as("отправка обязана уходить на пул писем")
                .isTrue();
    }

    /** Ни один метод отправителя не должен принимать сущность — только снимок. */
    @Test
    void theDispatcherAcceptsNoEntities() {
        for (Method method : MailDispatcher.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("send")) {
                continue;
            }
            assertThat(method.getParameterTypes())
                    .allSatisfy(type -> assertThat(type.getPackageName())
                            .as("%s принимает %s", method.getName(), type.getName())
                            .doesNotStartWith("com.petmarketplace.domain"));
        }
    }

    @Test
    void shouldSnapshotTheListingForASubscriptionMatch() {
        User user = user("ann@example.com", "Ann", "Smith");
        Listing listing = mock(Listing.class);
        UUID listingId = UUID.randomUUID();
        when(listing.getId()).thenReturn(listingId);
        when(listing.getTitle()).thenReturn("Labrador puppy");
        when(listing.getPrice()).thenReturn(new BigDecimal("30000.00"));
        when(listing.getCurrency()).thenReturn("RUB");
        when(listing.getLocationCity()).thenReturn("Москва");

        service.sendSubscriptionMatch(user, listing);

        ArgumentCaptor<SubscriptionMatchMail> captor =
                ArgumentCaptor.forClass(SubscriptionMatchMail.class);
        verify(dispatcher).sendSubscriptionMatch(captor.capture());
        SubscriptionMatchMail mail = captor.getValue();
        assertThat(mail.recipientEmail()).isEqualTo("ann@example.com");
        assertThat(mail.userName()).isEqualTo("Ann Smith");
        assertThat(mail.listingId()).isEqualTo(listingId);
        assertThat(mail.listingTitle()).isEqualTo("Labrador puppy");
        assertThat(mail.listingPrice()).isEqualByComparingTo("30000.00");
        assertThat(mail.listingCurrency()).isEqualTo("RUB");
        assertThat(mail.listingCity()).isEqualTo("Москва");
    }

    /**
     * Заголовок объявления шаблон брал сам, выражением {@code booking.listing.title} — то есть
     * обходил ленивую связь уже на потоке пула. Теперь связь разворачивается здесь.
     */
    @Test
    void shouldResolveTheBookingListingTitleUpFront() {
        User buyer = user("buyer@example.com", "Bob", null);
        User seller = user("seller@example.com", "Sam", null);
        Listing listing = mock(Listing.class);
        when(listing.getTitle()).thenReturn("Maine Coon kitten");
        Booking booking = booking(buyer, seller, listing);

        service.sendBookingStatusUpdate(buyer, booking, BookingStatus.CONFIRMED);

        BookingStatusMail mail = captureBookingMail();
        assertThat(mail.listingTitle()).isEqualTo("Maine Coon kitten");
        assertThat(mail.buyer()).isTrue();
        assertThat(mail.otherPartyName()).isEqualTo("Sam");
    }

    @Test
    void shouldTreatTheSellerAsTheOtherPartyForABuyerAndViceVersa() {
        User buyer = user("buyer@example.com", "Bob", null);
        User seller = user("seller@example.com", "Sam", null);
        Booking booking = booking(buyer, seller, mock(Listing.class));

        service.sendBookingStatusUpdate(seller, booking, BookingStatus.CANCELLED);

        BookingStatusMail mail = captureBookingMail();
        assertThat(mail.buyer()).isFalse();
        assertThat(mail.otherPartyName()).isEqualTo("Bob");
        assertThat(mail.recipientEmail()).isEqualTo("seller@example.com");
    }

    @Test
    void shouldFallBackToTheEmailLocalPartWhenTheNameIsMissing() {
        User user = user("nameless@example.com", null, null);

        service.sendSubscriptionMatch(user, mock(Listing.class));

        ArgumentCaptor<SubscriptionMatchMail> captor =
                ArgumentCaptor.forClass(SubscriptionMatchMail.class);
        verify(dispatcher).sendSubscriptionMatch(captor.capture());
        assertThat(captor.getValue().userName()).isEqualTo("nameless");
    }

    // --- helpers ---

    private boolean isAsync(Method method) {
        Async async = method.getAnnotation(Async.class);
        return async != null && "emailTaskExecutor".equals(async.value());
    }

    private User user(String email, String firstName, String lastName) {
        User user = mock(User.class);
        when(user.getEmail()).thenReturn(email);
        when(user.getFirstName()).thenReturn(firstName);
        when(user.getLastName()).thenReturn(lastName);
        when(user.getId()).thenReturn(UUID.randomUUID());
        return user;
    }

    private Booking booking(User buyer, User seller, Listing listing) {
        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(UUID.randomUUID());
        when(booking.getBuyer()).thenReturn(buyer);
        when(booking.getSeller()).thenReturn(seller);
        when(booking.getListing()).thenReturn(listing);
        return booking;
    }

    private BookingStatusMail captureBookingMail() {
        ArgumentCaptor<BookingStatusMail> captor = ArgumentCaptor.forClass(BookingStatusMail.class);
        verify(dispatcher).sendBookingStatus(captor.capture());
        return captor.getValue();
    }
}
