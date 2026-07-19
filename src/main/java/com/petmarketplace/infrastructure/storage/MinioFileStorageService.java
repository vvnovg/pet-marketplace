package com.petmarketplace.infrastructure.storage;

import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "storage", name = "provider", havingValue = "minio", matchIfMissing = false)
public class MinioFileStorageService implements FileStorageService {

    @Override
    public String store(String bucketName, String objectKey, InputStream data, long size, String contentType) {
        log.info("Storing object {}/{} via MinIO (not implemented yet)", bucketName, objectKey);
        return "";
    }

    @Override
    public InputStream retrieve(String bucketName, String objectKey) {
        throw new UnsupportedOperationException("MinIO retrieve not implemented yet");
    }

    @Override
    public void delete(String bucketName, String objectKey) {
        log.info("Deleting object {}/{} via MinIO (not implemented yet)", bucketName, objectKey);
    }

    @Override
    public String getPublicUrl(String bucketName, String objectKey) {
        return "";
    }
}
