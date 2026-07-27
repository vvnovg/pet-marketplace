package com.petmarketplace.application.file.controller;

import com.petmarketplace.infrastructure.storage.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.InputStream;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
@Tag(name = "Files", description = "Serves uploaded files")
public class FileController {

    private final FileStorageService fileStorageService;

    @Operation(summary = "Download a stored file")
    @ApiResponse(responseCode = "200", description = "File returned")
    @ApiResponse(responseCode = "404", description = "File not found")
    @GetMapping("/{bucket}/{*objectKey}")
    public ResponseEntity<Resource> download(@PathVariable String bucket,
                                             @PathVariable String objectKey) {
        // PathPattern's {*var} capture keeps the leading slash; the storage layer wants it bare.
        String key = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
        InputStream stream = fileStorageService.retrieve(bucket, key);
        MediaType contentType = safeContentType(key);
        return ResponseEntity.ok()
                // Object keys embed a UUID, so a stored file never changes under the same URL.
                .cacheControl(cacheControlFor(bucket))
                .contentType(contentType)
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(stream));
    }

    /**
     * "messages" requires authentication (see SecurityConfig), so it must never be marked
     * cachePublic() — a shared/CDN cache introduced later could serve one user's attachment to
     * another. No shared cache sits in front of this deployment today, so the distinction is
     * inert, but cachePrivate() costs nothing and keeps the header honest. Other buckets (avatars,
     * images) are public listing/profile content and keep the original public directive.
     */
    private static CacheControl cacheControlFor(String bucket) {
        CacheControl cacheControl = CacheControl.maxAge(Duration.ofDays(365));
        return "messages".equals(bucket) ? cacheControl.cachePrivate() : cacheControl.cachePublic();
    }

    /**
     * The object key's extension comes from the client-supplied filename at upload time, and
     * upload validation only checks the (spoofable) declared Content-Type. Echoing the derived
     * type back would let an attacker have arbitrary content served as text/html from the
     * frontend's own origin, which proxies this endpoint. Only plain image types are honoured;
     * SVG is excluded because it can execute script.
     */
    private static MediaType safeContentType(String objectKey) {
        return MediaTypeFactory.getMediaType(objectKey)
                .filter(type -> type.getType().equals("image"))
                .filter(type -> !type.getSubtype().equals("svg+xml"))
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
    }
}
