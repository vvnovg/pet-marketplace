package com.petmarketplace.application.file.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.petmarketplace.IntegrationTestBase;
import com.petmarketplace.domain.user.entity.Role;
import com.petmarketplace.infrastructure.storage.FileStorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;

/**
 * {@link #storePng} and the other direct {@code fileStorageService.store} calls below write
 * through the in-JVM {@link FileStorageService} (local disk, {@code build/test-uploads} on the
 * host running the JVM). In stand mode ({@code gradle testOnStand}) the {@code restClient} talks
 * HTTP to an external stand whose files live in that stand's own container/volume — an unrelated
 * filesystem the test JVM never writes to — so any test that seeds a file this way would 404
 * instead of 200. Those tests are skipped in stand mode via {@code Assumptions.assumeFalse}.
 * {@code uploadedAvatarIsDownloadableAtTheReturnedUrl} seeds through the real HTTP upload
 * endpoint instead, so it exercises the stand's own storage and correctly keeps running there.
 * {@code requestingADirectoryReturnsNotFound} also seeds via {@code storePng} but is deliberately
 * left unguarded: its {@code NOT_FOUND} assertion holds either way, since the path is a directory
 * in embedded mode and simply absent on the stand.
 */
class FileControllerTest extends IntegrationTestBase {

    private static final String AVATAR_PREFIX = "avatars/";

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
        Assumptions.assumeFalse(STAND_MODE, "seeds through the local FileStorageService, not the stand's filesystem");
        String key = storePng("avatars", "avatars/" + UUID.randomUUID());

        ResponseEntity<String> res = getStatus("/files/avatars/" + key, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("png-bytes");
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    }

    @Test
    void listingImageIsServedToAnonymous() {
        Assumptions.assumeFalse(STAND_MODE, "seeds through the local FileStorageService, not the stand's filesystem");
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
        Assumptions.assumeFalse(STAND_MODE, "seeds through the local FileStorageService, not the stand's filesystem");
        var buyer = createUniqueUser(Role.BUYER);
        String key = storePng("messages", "messages/" + UUID.randomUUID());

        ResponseEntity<String> res = getStatus("/files/messages/" + key, buyer);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("png-bytes");
    }

    @Test
    void messageAttachmentIsServedWithPrivateCacheControl() {
        Assumptions.assumeFalse(STAND_MODE, "seeds through the local FileStorageService, not the stand's filesystem");
        var buyer = createUniqueUser(Role.BUYER);
        String key = storePng("messages", "messages/" + UUID.randomUUID());

        ResponseEntity<String> res = getStatus("/files/messages/" + key, buyer);

        assertThat(res.getHeaders().getCacheControl()).contains("private");
    }

    @Test
    void avatarIsServedWithPublicCacheControl() {
        Assumptions.assumeFalse(STAND_MODE, "seeds through the local FileStorageService, not the stand's filesystem");
        String key = storePng("avatars", "avatars/" + UUID.randomUUID());

        ResponseEntity<String> res = getStatus("/files/avatars/" + key, null);

        assertThat(res.getHeaders().getCacheControl()).contains("public");
    }

    @Test
    void unknownKeyReturnsNotFound() {
        assertThat(getStatus("/files/avatars/avatars/" + UUID.randomUUID() + "/nope.png", null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // The traversal guard itself lives in LocalFileStorageService.resolve and is covered by
    // LocalFileStorageServiceTest (traversing keys, absolute keys, "..", ".", traversing bucket
    // names). A literal "../" never reaches this controller: the servlet container collapses it
    // before the filter chain runs. These two tests cover the error paths that DO reach us.

    @Test
    void requestingADirectoryReturnsNotFound() {
        String key = storePng("avatars", "avatars/" + UUID.randomUUID());
        String directory = key.substring(0, key.lastIndexOf('/'));

        assertThat(getStatus("/files/avatars/" + directory, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void emptyObjectKeyIsRejected() {
        assertThat(getStatus("/files/avatars/", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void nonImageExtensionIsServedAsOctetStreamWithNosniff() {
        Assumptions.assumeFalse(STAND_MODE, "seeds through the local FileStorageService, not the stand's filesystem");
        String key = "avatars/" + UUID.randomUUID() + "/payload.html";
        fileStorageService.store("avatars", key,
                new ByteArrayInputStream("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)),
                25, "text/html");

        ResponseEntity<String> res = getStatus("/files/avatars/" + key, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(res.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void svgIsNotServedAsSvgBecauseItCanExecuteScript() {
        Assumptions.assumeFalse(STAND_MODE, "seeds through the local FileStorageService, not the stand's filesystem");
        String key = "avatars/" + UUID.randomUUID() + "/x.svg";
        fileStorageService.store("avatars", key,
                new ByteArrayInputStream("<svg/>".getBytes(StandardCharsets.UTF_8)),
                6, "image/svg+xml");

        assertThat(getStatus("/files/avatars/" + key, null).getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
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

    @Test
    void replacingAnAvatarDeletesThePreviousFile() {
        var buyer = createUniqueUser(Role.BUYER);

        String firstUrl = uploadAvatar(buyer, "first-avatar-bytes");
        String secondUrl = uploadAvatar(buyer, "second-avatar-bytes");
        assertThat(secondUrl).isNotEqualTo(firstUrl);

        // The replacement is readable; the object it replaced is gone rather than left
        // orphaned on disk behind an immutable, publicly readable key.
        assertThat(getStatus(secondUrl.replace("/api/proxy", ""), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(getStatus(firstUrl.replace("/api/proxy", ""), null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void replacingAForeignAvatarUrlDeletesNothing() {
        Assumptions.assumeFalse(STAND_MODE, "seeds through the local FileStorageService, not the stand's filesystem");
        var buyer = createUniqueUser(Role.BUYER);

        // A URL this service never stored — the demo seed uses frontend-static paths like this.
        // Reconstructing a key from it by filename (as ListingService does for listing images)
        // would target this innocent object, so the guard must skip deletion entirely.
        var user = userRepository.findByEmail(buyer.email()).orElseThrow();
        user.setAvatarUrl("/animals/beagle.jpg");
        userRepository.save(user);
        String bystanderKey = AVATAR_PREFIX + buyer.id() + "/beagle.jpg";
        fileStorageService.store("avatars", bystanderKey,
                new ByteArrayInputStream("bystander".getBytes(StandardCharsets.UTF_8)),
                9, "image/png");

        String newUrl = uploadAvatar(buyer, "replacement-bytes");

        assertThat(getStatus(newUrl.replace("/api/proxy", ""), null).getBody())
                .isEqualTo("replacement-bytes");
        ResponseEntity<String> bystander = getStatus("/files/avatars/" + bystanderKey, null);
        assertThat(bystander.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bystander.getBody()).isEqualTo("bystander");
    }

    /** Uploads an avatar through the real HTTP endpoint and returns the stored URL. */
    private String uploadAvatar(com.petmarketplace.IntegrationTestBase.TestUser user, String content) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", content.getBytes(StandardCharsets.UTF_8), MediaType.IMAGE_PNG)
                .filename("a.png");

        ResponseEntity<String> upload = restClient.post()
                .uri("/users/me/avatar")
                .body(builder.build())
                .headers(authHeaders(user))
                .retrieve()
                .onStatus(s -> true, (req, r) -> { })
                .toEntity(String.class);
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parseAvatarUrl(upload);
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
