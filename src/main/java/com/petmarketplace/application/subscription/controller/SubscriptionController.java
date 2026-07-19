package com.petmarketplace.application.subscription.controller;

import com.petmarketplace.application.subscription.dto.SubscriptionCreateRequest;
import com.petmarketplace.application.subscription.dto.SubscriptionResponse;
import com.petmarketplace.application.subscription.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/subscriptions")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Subscriptions", description = "Saved search filters for email notifications")
@SecurityRequirement(name = "bearer-jwt")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @Operation(summary = "List current user subscriptions")
    @ApiResponse(responseCode = "200", description = "Subscriptions retrieved")
    @GetMapping
    public List<SubscriptionResponse> list() {
        return subscriptionService.getSubscriptions();
    }

    @Operation(summary = "Create a new search subscription")
    @ApiResponse(responseCode = "201", description = "Subscription created")
    @ApiResponse(responseCode = "400", description = "Invalid subscription data")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionResponse create(@RequestBody @Valid SubscriptionCreateRequest request) {
        return subscriptionService.createSubscription(request);
    }

    @Operation(summary = "Delete a subscription")
    @ApiResponse(responseCode = "204", description = "Subscription deleted")
    @ApiResponse(responseCode = "404", description = "Subscription not found")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        subscriptionService.deleteSubscription(id);
    }
}
