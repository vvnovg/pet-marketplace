package com.petmarketplace.application.file.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.petmarketplace.IntegrationTestBase;
import com.petmarketplace.domain.user.entity.Role;
import com.petmarketplace.infrastructure.storage.FileStorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;

class FileControllerTest extends IntegrationTestBase {

    @Autowired
    private FileStorageService fileStorageService;

    private String storePng(String bucket, String keyPrefix) {
        String key = keyPrefix + "/" + UUID.randomUUID() + ".png";
        fileStorageService.store(bucket, key,
                new ByteArrayInputStream("png-bytes".getBytes(StandardCharsets.UTF_8)),
                9, "image/png");
        return key;
    }

    @Test
    void avatarIsServedToAnonymousWithImageContentType() {
        String key = storePng("avatars", "avatars/" + UUID.randomUUID());

        ResponseEntity<String> res = getStatus("/files/avatars/" + key, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("png-bytes");
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    }

    @Test
    void listingImageIsServedToAnonymous() {
        String key = storePng("images", "listings/" + UUID.randomUUID());

        ResponseEntity<String> res = getStatus("/files/images/" + key, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("png-bytes");
    }

    @Test
    void messageAttachmentRequiresAuthentication() {
        String key = storePng("messages", "messages/" + UUID.randomUUID());

        assertThat(getStatus("/files/messages/" + key, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void messageAttachmentIsServedToAuthenticatedUser() {
        var buyer = createUniqueUser(Role.BUYER);
        String key = storePng("messages", "messages/" + UUID.randomUUID());

        ResponseEntity<String> res = getStatus("/files/messages/" + key, buyer);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("png-bytes");
    }

    @Test
    void unknownKeyReturnsNotFound() {
        assertThat(getStatus("/files/avatars/avatars/" + UUID.randomUUID() + "/nope.png", null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void traversingKeyIsRejected() {
        // A literal "../../../" traversal is silently collapsed by the embedded servlet
        // container's own URI normalisation before it ever reaches Spring (verified empirically:
        // it lands outside the app's context path and comes back 404, not 400 — see the Task 2
        // report for the investigation). A backslash-based traversal attempt survives that
        // normalisation and is rejected upstream of the controller; either the container or
        // Spring Security's StrictHttpFirewall answers, and distinguishing them would test
        // framework internals, so this only asserts the 400.
        assertThat(getStatus("/files/avatars/..\\..\\etc\\passwd", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void uploadedAvatarIsDownloadableAtTheReturnedUrl() {
        var buyer = createUniqueUser(Role.BUYER);
        byte[] payload = "real-avatar-bytes".getBytes(StandardCharsets.UTF_8);
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", payload, MediaType.IMAGE_PNG).filename("a.png");

        ResponseEntity<String> upload = restClient.post()
                .uri("/users/me/avatar")
                .body(builder.build())
                .headers(authHeaders(buyer))
                .retrieve()
                .onStatus(s -> true, (req, r) -> { })
                .toEntity(String.class);
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.OK);

        String avatarUrl = parseAvatarUrl(upload);
        assertThat(avatarUrl).startsWith("/api/proxy/files/avatars/");

        // The stored URL carries the frontend's proxy prefix; strip it to hit the backend directly.
        String backendPath = avatarUrl.replace("/api/proxy", "");
        ResponseEntity<String> download = getStatus(backendPath, null);

        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getBody()).isEqualTo("real-avatar-bytes");
    }

    private String parseAvatarUrl(ResponseEntity<String> res) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(res.getBody()).get("avatarUrl").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
