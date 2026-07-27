package com.petmarketplace.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petmarketplace.exception.ResourceNotFoundException;
import com.petmarketplace.exception.ValidationException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit test: no Spring context, the service is constructed directly. */
class LocalFileStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalFileStorageService service;

    @BeforeEach
    void setUp() {
        service = new LocalFileStorageService(tempDir.toString(), "/api/proxy/files");
    }

    private static InputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void storeWritesFileAndReturnsPublicUrl() throws Exception {
        String key = "avatars/" + UUID.randomUUID() + "/pic.png";

        String url = service.store("avatars", key, bytes("hello"), 5, "image/png");

        assertThat(url).isEqualTo("/api/proxy/files/avatars/" + key);
        Path written = tempDir.resolve("avatars").resolve(key);
        assertThat(written).exists();
        assertThat(Files.readString(written)).isEqualTo("hello");
    }

    @Test
    void storeCreatesMissingParentDirectories() {
        String key = "deep/nested/path/" + UUID.randomUUID() + ".png";

        service.store("images", key, bytes("x"), 1, "image/png");

        assertThat(tempDir.resolve("images").resolve(key)).exists();
    }

    @Test
    void storeOverwritesAnExistingFile() throws Exception {
        String key = "avatars/same.png";
        service.store("avatars", key, bytes("first"), 5, "image/png");

        service.store("avatars", key, bytes("second"), 6, "image/png");

        assertThat(Files.readString(tempDir.resolve("avatars").resolve(key))).isEqualTo("second");
    }

    @Test
    void retrieveReturnsExactlyTheStoredBytes() throws Exception {
        String key = "images/listing.png";
        byte[] payload = {0, 1, 2, (byte) 0xFF, (byte) 0xFE, 127};
        service.store("images", key, new ByteArrayInputStream(payload), payload.length, "image/png");

        try (InputStream in = service.retrieve("images", key)) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void retrieveThrowsNotFoundForMissingFile() {
        assertThatThrownBy(() -> service.retrieve("avatars", "nope/missing.png"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteRemovesTheFile() {
        String key = "avatars/gone.png";
        service.store("avatars", key, bytes("x"), 1, "image/png");

        service.delete("avatars", key);

        assertThat(tempDir.resolve("avatars").resolve(key)).doesNotExist();
    }

    @Test
    void deleteIsSilentForMissingFile() {
        assertThatCode(() -> service.delete("avatars", "never/existed.png"))
                .doesNotThrowAnyException();
    }

    @Test
    void getPublicUrlJoinsPrefixBucketAndKey() {
        assertThat(service.getPublicUrl("images", "listings/abc/def.png"))
                .isEqualTo("/api/proxy/files/images/listings/abc/def.png");
    }

    @Test
    void publicUrlPrefixTrailingSlashIsNormalised() {
        var withSlash = new LocalFileStorageService(tempDir.toString(), "/api/proxy/files/");

        assertThat(withSlash.getPublicUrl("avatars", "a.png"))
                .isEqualTo("/api/proxy/files/avatars/a.png");
    }

    @Test
    void traversingObjectKeyIsRejectedByEveryMethod() {
        String escape = "../../../etc/passwd";

        assertThatThrownBy(() -> service.store("avatars", escape, bytes("x"), 1, "image/png"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.retrieve("avatars", escape))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.delete("avatars", escape))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.getPublicUrl("avatars", escape))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void absoluteObjectKeyIsRejected() {
        assertThatThrownBy(() -> service.retrieve("avatars", "/etc/passwd"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void blankBucketOrKeyIsRejected() {
        assertThatThrownBy(() -> service.retrieve("", "a.png"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.retrieve("avatars", "  "))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void traversalThatStaysInsideBaseIsAllowed() throws Exception {
        // "a/../b.png" normalises to "b.png", which is still under the base path.
        service.store("avatars", "a/../b.png", bytes("ok"), 2, "image/png");

        assertThat(Files.readString(tempDir.resolve("avatars").resolve("b.png"))).isEqualTo("ok");
    }
}
