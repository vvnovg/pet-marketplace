package com.petmarketplace.application.imports;

import com.petmarketplace.domain.importjob.entity.AnimalImportJob;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "storage.provider", havingValue = "minio")
@ConditionalOnProperty(name = "import.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AnimalImportScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnimalImportScheduler.class);

    private final MinioClient minioClient;
    private final AnimalImportJobService jobService;
    private final AnimalImportService importService;

    @Value("${import.scheduler.bucket:imports}")
    private String bucket;

    @Value("${import.scheduler.prefix:pending/}")
    private String prefix;

    public AnimalImportScheduler(MinioClient minioClient,
                                  AnimalImportJobService jobService,
                                  AnimalImportService importService) {
        this.minioClient = minioClient;
        this.jobService = jobService;
        this.importService = importService;
    }

    @Scheduled(fixedDelayString = "${import.scheduler.poll-interval-ms:60000}")
    public void pollImportBucket() {
        log.debug("Polling MinIO bucket '{}' with prefix '{}'", bucket, prefix);
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .recursive(false)
                            .build());

            for (Result<Item> result : results) {
                try {
                    Item item = result.get();
                    if (item.isDir() || item.objectName().equals(prefix)) {
                        continue;
                    }
                    String objectKey = item.objectName();
                    log.info("Found new import file: {}/{}", bucket, objectKey);
                    AnimalImportJob job = jobService.create(bucket, objectKey);
                    importService.importAnimals(job.getId(), bucket, objectKey);
                } catch (Exception e) {
                    log.error("Error processing import file from bucket '{}'", bucket, e);
                }
            }
        } catch (Exception e) {
            log.error("Error listing objects in import bucket '{}'", bucket, e);
        }
    }
}
