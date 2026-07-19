package com.petmarketplace.application.message.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.petmarketplace.IntegrationTestBase;
import com.petmarketplace.application.message.dto.ConversationResponse;
import com.petmarketplace.application.message.dto.MessageResponse;
import com.petmarketplace.application.message.dto.MessageSendRequest;
import com.petmarketplace.domain.user.entity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;

class MessageControllerTest extends IntegrationTestBase {

    @Test
    void shouldSendMessageAndRetrieveConversations() {
        TestUser sender = createUniqueUser(Role.BUYER);
        TestUser receiver = createUniqueUser(Role.SELLER);

        MessageSendRequest sendRequest = new MessageSendRequest(receiver.id(), null, "Hello, is the puppy still available?");

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("data", sendRequest, MediaType.APPLICATION_JSON);
        var multipartBody = builder.build();

        ResponseEntity<MessageResponse> sendResponse = restClient.post()
                .uri("/messages")
                .body(multipartBody)
                .headers(authHeaders(sender))
                .retrieve()
                .toEntity(MessageResponse.class);

        assertThat(sendResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sendResponse.getBody()).isNotNull();
        assertThat(sendResponse.getBody().content()).isEqualTo("Hello, is the puppy still available?");
        assertThat(sendResponse.getBody().sender().id()).isEqualTo(sender.id());
        assertThat(sendResponse.getBody().receiver().id()).isEqualTo(receiver.id());

        ResponseEntity<ConversationResponse[]> conversationsResponse = restClient.get()
                .uri("/messages")
                .headers(authHeaders(receiver))
                .retrieve()
                .toEntity(ConversationResponse[].class);

        assertThat(conversationsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(conversationsResponse.getBody()).isNotNull();
        assertThat(conversationsResponse.getBody()).hasSize(1);
        assertThat(conversationsResponse.getBody()[0].partner().id()).isEqualTo(sender.id());
    }
}
