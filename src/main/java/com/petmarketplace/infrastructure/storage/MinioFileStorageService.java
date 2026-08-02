package com.petmarketplace.infrastructure.storage;

import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.InputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "storage.provider", havingValue = "minio")
public class MinioFileStorageService implements FileStorageService {

    private final MinioClient client;

    public MinioFileStorageService(MinioClient client) {
        this.client = client;
    }

    @Override
    public String store(String bucket, String objectKey, InputStream data,
                        long size, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(data, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to store object: " + bucket + "/" + objectKey, e);
        }
        return getPublicUrl(bucket, objectKey);
    }

    @Override
    public InputStream retrieve(String bucket, String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve object: " + bucket + "/" + objectKey, e);
        }
    }

    @Override
    public void delete(String bucket, String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete object: " + bucket + "/" + objectKey, e);
        }
    }

    @Override
    public void move(String bucket, String sourceKey, String targetKey) {
        // Объектное хранилище не умеет переименовывать: copy + remove — единственный способ,
        // и копирование целиком на стороне сервера, файл через приложение не течёт.
        try {
            client.copyObject(CopyObjectArgs.builder()
                    .bucket(bucket)
                    .object(targetKey)
                    .source(CopySource.builder()
                            .bucket(bucket)
                            .object(sourceKey)
                            .build())
                    .build());
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(sourceKey)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to move object: " + bucket + "/" + sourceKey + " to " + targetKey, e);
        }
    }

    @Override
    public String getPublicUrl(String bucket, String objectKey) {
        // MinIO public URL: endpoint/bucket/objectKey
        // In production this would be a CDN URL; for now return a relative path
        // that the frontend proxy resolves
        return "/api/proxy/files/" + bucket + "/" + objectKey;
    }
}
