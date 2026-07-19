package com.petmarketplace.infrastructure.storage;

import java.io.InputStream;

public interface FileStorageService {

    String store(String bucketName, String objectKey, InputStream data, long size, String contentType);

    InputStream retrieve(String bucketName, String objectKey);

    void delete(String bucketName, String objectKey);

    String getPublicUrl(String bucketName, String objectKey);
}
