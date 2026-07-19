package com.petmarketplace.application.booking.service;

import com.petmarketplace.application.booking.dto.BookingDealSummaryResponse;
import com.petmarketplace.application.booking.dto.BookingResponse;
import com.petmarketplace.application.booking.mapper.BookingMapper;
import com.petmarketplace.domain.booking.entity.Booking;
import com.petmarketplace.domain.booking.entity.BookingStatus;
import com.petmarketplace.domain.booking.repository.BookingRepository;
import com.petmarketplace.domain.listing.entity.Listing;
import com.petmarketplace.domain.listing.entity.ListingStatus;
import com.petmarketplace.domain.listing.repository.ListingRepository;
import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.domain.user.repository.UserRepository;
import com.petmarketplace.exception.BusinessException;
import com.petmarketplace.exception.ResourceNotFoundException;
import com.petmarketplace.infrastructure.notification.EmailNotificationService;
import com.petmarketplace.infrastructure.security.UserDetailsImpl;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BookingService {

    private final BookingRepository bookingRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final BookingMapper bookingMapper;
    private final EmailNotificationService emailNotificationService;

    public BookingResponse createBooking(UUID listingId, String message) {
        return createBooking(currentUser(), listingId, message);
    }

    public BookingResponse createBooking(User buyer, UUID listingId, String message) {
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing not found", listingId));

        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new BusinessException("Booking is only available for active listings");
        }
        if (Objects.equals(buyer.getId(), listing.getSeller().getId())) {
            throw new BusinessException("Buyer cannot book their own listing");
        }
        if (bookingRepository.existsByListingIdAndBuyerIdAndStatusIn(
                listingId, buyer.getId(), List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED))) {
            throw new BusinessException("Active booking already exists for this listing");
        }

        Booking booking = Booking.builder()
                .listing(listing)
                .buyer(buyer)
                .seller(listing.getSeller())
                .status(BookingStatus.PENDING)
                .message(message)
                .build();

        Booking saved = bookingRepository.save(booking);
        log.debug("Created booking {} for listing {} by buyer {}", saved.getId(), listingId, buyer.getId());

        initializeForNotification(saved);
        emailNotificationService.sendBookingStatusUpdate(saved.getSeller(), saved, saved.getStatus());
        return bookingMapper.toResponse(saved);
    }

    public BookingResponse getBookingForUser(UUID bookingId) {
        return getBookingForUser(currentUser(), bookingId);
    }

    @Transactional(readOnly = true)
    public BookingResponse getBookingForUser(User user, UUID bookingId) {
        Booking booking = findBooking(bookingId);
        requireParticipant(user, booking);
        return bookingMapper.toResponse(booking);
    }

    public Page<BookingResponse> getBookingsForUser(Pageable pageable) {
        return getBookingsForUser(currentUser(), pageable);
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> getBookingsForUser(User user, Pageable pageable) {
        Page<Booking> page = bookingRepository.findByBuyerIdOrSellerId(user.getId(), user.getId(), pageable);
        return page.map(bookingMapper::toResponse);
    }

    public BookingResponse confirmBooking(UUID bookingId) {
        return confirmBooking(currentUser(), bookingId);
    }

    public BookingResponse confirmBooking(User seller, UUID bookingId) {
        Booking booking = findBooking(bookingId);
        requireSeller(seller, booking);

        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BusinessException("Only pending bookings can be confirmed");
        }

        booking.setStatus(BookingStatus.CONFIRMED);
        Listing listing = booking.getListing();
        listing.setStatus(ListingStatus.RESERVED);
        listingRepository.save(listing);

        Booking saved = bookingRepository.save(booking);
        log.debug("Confirmed booking {} and reserved listing {}", saved.getId(), listing.getId());

        notifyBookingParticipants(saved, saved.getStatus());
        return bookingMapper.toResponse(saved);
    }

    public BookingResponse cancelBooking(UUID bookingId) {
        return cancelBooking(currentUser(), bookingId);
    }

    public BookingResponse cancelBooking(User user, UUID bookingId) {
        Booking booking = findBooking(bookingId);
        requireParticipant(user, booking);

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new BusinessException("Booking is already cancelled");
        }
        if (booking.getStatus() == BookingStatus.COMPLETED) {
            throw new BusinessException("Completed bookings cannot be cancelled");
        }

        BookingStatus previousStatus = booking.getStatus();
        booking.setStatus(BookingStatus.CANCELLED);

        if (previousStatus == BookingStatus.CONFIRMED) {
            Listing listing = booking.getListing();
            listing.setStatus(ListingStatus.ACTIVE);
            listingRepository.save(listing);
        }

        Booking saved = bookingRepository.save(booking);
        log.debug("Cancelled booking {} by user {}", saved.getId(), user.getId());

        notifyBookingParticipants(saved, saved.getStatus());
        return bookingMapper.toResponse(saved);
    }

    public BookingResponse completeBooking(UUID bookingId) {
        return completeBooking(currentUser(), bookingId);
    }

    public BookingResponse completeBooking(User seller, UUID bookingId) {
        Booking booking = findBooking(bookingId);
        requireSeller(seller, booking);

        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException("Only confirmed bookings can be completed");
        }

        booking.setStatus(BookingStatus.COMPLETED);
        Listing listing = booking.getListing();
        listing.setStatus(ListingStatus.SOLD);
        listingRepository.save(listing);

        Booking saved = bookingRepository.save(booking);
        log.debug("Completed booking {} and marked listing {} as sold", saved.getId(), listing.getId());

        notifyBookingParticipants(saved, saved.getStatus());
        return bookingMapper.toResponse(saved);
    }

    public BookingDealSummaryResponse getDealSummary(UUID bookingId) {
        User user = currentUser();
        Booking booking = findBooking(bookingId);
        requireParticipant(user, booking);
        return bookingMapper.toDealSummary(booking);
    }

    private Booking findBooking(UUID bookingId) {
        return bookingRepository.findByIdWithDetails(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found", bookingId));
    }

    private boolean isParticipant(User user, Booking booking) {
        return Objects.equals(user.getId(), booking.getBuyer().getId())
                || Objects.equals(user.getId(), booking.getSeller().getId());
    }

    private void requireParticipant(User user, Booking booking) {
        if (!isParticipant(user, booking)) {
            throw new BusinessException("You are not allowed to access this booking");
        }
    }

    private void requireSeller(User user, Booking booking) {
        if (!Objects.equals(user.getId(), booking.getSeller().getId())) {
            throw new BusinessException("Only the seller can perform this action");
        }
    }

    private void notifyBookingParticipants(Booking booking, BookingStatus status) {
        initializeForNotification(booking);
        emailNotificationService.sendBookingStatusUpdate(booking.getBuyer(), booking, status);
        if (!Objects.equals(booking.getBuyer().getId(), booking.getSeller().getId())) {
            emailNotificationService.sendBookingStatusUpdate(booking.getSeller(), booking, status);
        }
    }

    /**
     * Force-initialize the associations the async email template reads (buyer, seller, listing)
     * so the {@code @Async} notification thread — which runs without a Hibernate session — never
     * triggers lazy loading. Without this, concurrent proxy initialization between the async
     * email thread and the request thread corrupts Hibernate state
     * ("Illegal pop() with non-matching JdbcValuesSourceProcessingState") and the endpoint
     * returns HTTP 500.
     */
    private void initializeForNotification(Booking booking) {
        Hibernate.initialize(booking.getBuyer());
        Hibernate.initialize(booking.getSeller());
        Hibernate.initialize(booking.getListing());
    }

    private User currentUser() {
        return getAuthenticatedUser()
                .orElseThrow(() -> new BusinessException("User not authenticated"));
    }

    private Optional<User> getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserDetailsImpl details)) {
            return Optional.empty();
        }
        return userRepository.findByEmail(details.getEmail());
    }
}
