package com.petmarketplace.infrastructure.storage;

import com.petmarketplace.exception.BusinessException;
import com.petmarketplace.exception.ResourceNotFoundException;
import com.petmarketplace.exception.ValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "storage", name = "provider", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    private final Path basePath;
    private final String publicBasePath;

    public LocalFileStorageService(
            @Value("${storage.local.base-path}") String basePath,
            @Value("${storage.public-base-path}") String publicBasePath) {
        this.basePath = Paths.get(basePath).toAbsolutePath().normalize();
        this.publicBasePath = publicBasePath.endsWith("/")
                ? publicBasePath.substring(0, publicBasePath.length() - 1)
                : publicBasePath;
    }

    @Override
    public String store(String bucketName, String objectKey, InputStream data, long size, String contentType) {
        Path target = resolve(bucketName, objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new BusinessException("Failed to store object %s/%s".formatted(bucketName, objectKey), ex);
        }
        return getPublicUrl(bucketName, objectKey);
    }

    @Override
    public InputStream retrieve(String bucketName, String objectKey) {
        Path target = resolve(bucketName, objectKey);
        if (!Files.isRegularFile(target)) {
            throw new ResourceNotFoundException("File not found: %s/%s".formatted(bucketName, objectKey));
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException ex) {
            throw new BusinessException("Failed to read object %s/%s".formatted(bucketName, objectKey), ex);
        }
    }

    @Override
    public void delete(String bucketName, String objectKey) {
        Path target = resolve(bucketName, objectKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new BusinessException("Failed to delete object %s/%s".formatted(bucketName, objectKey), ex);
        }
    }

    @Override
    public String getPublicUrl(String bucketName, String objectKey) {
        // Validate here too: a traversing key must never reach the database as a URL,
        // even though building the string touches no filesystem.
        resolve(bucketName, objectKey);
        return "%s/%s/%s".formatted(publicBasePath, bucketName, objectKey);
    }

    /**
     * Resolves {@code {bucket}/{key}} under the base path and refuses anything that escapes it.
     *
     * <p>{@code objectKey} reaches this class straight from a request URL in {@code FileController},
     * so the check runs on every operation rather than only on reads.
     */
    private Path resolve(String bucketName, String objectKey) {
        if (!StringUtils.hasText(bucketName) || !StringUtils.hasText(objectKey)) {
            throw new ValidationException("Bucket name and object key are required");
        }
        Path target;
        try {
            target = basePath.resolve(bucketName).resolve(objectKey).normalize();
        } catch (InvalidPathException ex) {
            throw new ValidationException("Invalid object key: " + objectKey);
        }
        if (!target.startsWith(basePath)) {
            throw new ValidationException("Invalid object key: " + objectKey);
        }
        return target;
    }
}
