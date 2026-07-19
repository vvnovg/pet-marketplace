package com.petmarketplace.application.booking.controller;

import com.petmarketplace.application.booking.dto.BookingResponse;
import com.petmarketplace.application.booking.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Bookings", description = "Reservation lifecycle for listings")
@SecurityRequirement(name = "bearer-jwt")
public class BookingController {

    private final BookingService bookingService;

    @Operation(summary = "List bookings for the current user")
    @ApiResponse(responseCode = "200", description = "Bookings retrieved")
    @GetMapping
    public Page<BookingResponse> list(@PageableDefault Pageable pageable) {
        return bookingService.getBookingsForUser(pageable);
    }

    @Operation(summary = "Get booking details")
    @ApiResponse(responseCode = "200", description = "Booking found")
    @ApiResponse(responseCode = "404", description = "Booking not found")
    @GetMapping("/{id}")
    public BookingResponse getById(@PathVariable UUID id) {
        return bookingService.getBookingForUser(id);
    }

    @Operation(summary = "Confirm a pending booking (seller only)")
    @ApiResponse(responseCode = "200", description = "Booking confirmed and listing reserved")
    @ApiResponse(responseCode = "400", description = "Booking cannot be confirmed")
    @ApiResponse(responseCode = "403", description = "Not the seller")
    @PutMapping("/{id}/confirm")
    public BookingResponse confirm(@PathVariable UUID id) {
        return bookingService.confirmBooking(id);
    }

    @Operation(summary = "Cancel a booking (seller or buyer)")
    @ApiResponse(responseCode = "200", description = "Booking cancelled")
    @ApiResponse(responseCode = "400", description = "Booking cannot be cancelled")
    @PutMapping("/{id}/cancel")
    public BookingResponse cancel(@PathVariable UUID id) {
        return bookingService.cancelBooking(id);
    }

    @Operation(summary = "Complete a confirmed booking (seller only)")
    @ApiResponse(responseCode = "200", description = "Booking completed and listing marked as sold")
    @ApiResponse(responseCode = "400", description = "Booking cannot be completed")
    @ApiResponse(responseCode = "403", description = "Not the seller")
    @PutMapping("/{id}/complete")
    public BookingResponse complete(@PathVariable UUID id) {
        return bookingService.completeBooking(id);
    }
}
