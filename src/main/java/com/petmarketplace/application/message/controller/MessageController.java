package com.petmarketplace.application.message.controller;

import com.petmarketplace.application.message.dto.ConversationResponse;
import com.petmarketplace.application.message.dto.MessageResponse;
import com.petmarketplace.application.message.dto.MessageSendRequest;
import com.petmarketplace.application.message.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/messages")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Messages", description = "Private messaging between users")
@SecurityRequirement(name = "bearer-jwt")
public class MessageController {

    private final MessageService messageService;

    @Operation(summary = "Get user conversations")
    @ApiResponse(responseCode = "200", description = "Conversations retrieved")
    @GetMapping
    public List<ConversationResponse> getConversations() {
        return messageService.getConversations();
    }

    @Operation(summary = "Get conversation with a specific user")
    @ApiResponse(responseCode = "200", description = "Messages retrieved")
    @GetMapping("/{userId}")
    public Page<MessageResponse> getConversation(
            @PathVariable UUID userId,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return messageService.getConversation(userId, pageable);
    }

    @Operation(summary = "Send a message with optional image attachment")
    @ApiResponse(responseCode = "200", description = "Message sent")
    @ApiResponse(responseCode = "400", description = "Invalid message data")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MessageResponse sendMessage(
            @RequestPart("data") @Valid MessageSendRequest request,
            @RequestPart(value = "attachment", required = false) MultipartFile attachment) {
        return messageService.sendMessage(request, attachment);
    }

    @Operation(summary = "Mark a received message as read")
    @ApiResponse(responseCode = "200", description = "Message marked as read")
    @ApiResponse(responseCode = "404", description = "Message not found")
    @PutMapping("/{id}/read")
    public MessageResponse markAsRead(@PathVariable UUID id) {
        return messageService.markAsRead(id);
    }
}
