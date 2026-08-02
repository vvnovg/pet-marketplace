package com.petmarketplace.infrastructure.storage;

import java.io.InputStream;

public interface FileStorageService {

    String store(String bucketName, String objectKey, InputStream data, long size, String contentType);

    InputStream retrieve(String bucketName, String objectKey);

    void delete(String bucketName, String objectKey);

    /**
     * Переносит объект внутри бакета, перезаписывая цель. Реализуется средствами самого
     * хранилища, а не чтением в память: файлы импорта — десятки мегабайт.
     */
    void move(String bucketName, String sourceKey, String targetKey);

    String getPublicUrl(String bucketName, String objectKey);
}
