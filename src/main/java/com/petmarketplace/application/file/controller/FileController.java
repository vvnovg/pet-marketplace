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
        MediaType contentType = MediaTypeFactory.getMediaType(key)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                // Object keys embed a UUID, so a stored file never changes under the same URL.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic())
                .contentType(contentType)
                .body(new InputStreamResource(stream));
    }
}
