package com.petmarketplace.application.imports;

import com.petmarketplace.domain.importjob.entity.AnimalImportJob;
import com.petmarketplace.domain.importjob.entity.ImportJobStatus;
import com.petmarketplace.infrastructure.storage.FileStorageService;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import java.util.UUID;
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
    private final FileStorageService storage;

    @Value("${import.scheduler.bucket:imports}")
    private String bucket;

    @Value("${import.scheduler.prefix:pending/}")
    private String prefix;

    @Value("${import.scheduler.processed-prefix:processed/}")
    private String processedPrefix;

    @Value("${import.scheduler.failed-prefix:failed/}")
    private String failedPrefix;

    public AnimalImportScheduler(MinioClient minioClient,
                                  AnimalImportJobService jobService,
                                  AnimalImportService importService,
                                  FileStorageService storage) {
        this.minioClient = minioClient;
        this.jobService = jobService;
        this.importService = importService;
        this.storage = storage;
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
                    // Файл уезжает из опрашиваемого префикса только после импорта, а импорт
                    // асинхронный и может не уложиться в интервал опроса — поэтому «новым»
                    // файл считается лишь пока на него не заведена задача. Без этой проверки
                    // опрос во время импорта дублировал бы все объявления из файла.
                    if (jobService.alreadyPickedUp(bucket, objectKey)) {
                        log.debug("Skipping already imported file: {}/{}", bucket, objectKey);
                        continue;
                    }
                    log.info("Found new import file: {}/{}", bucket, objectKey);
                    AnimalImportJob job = jobService.create(bucket, objectKey);
                    importService.importAnimals(job.getId(), bucket, objectKey)
                            .whenComplete((ignored, error) -> archive(job.getId(), objectKey));
                } catch (Exception e) {
                    log.error("Error processing import file from bucket '{}'", bucket, e);
                }
            }
        } catch (Exception e) {
            log.error("Error listing objects in import bucket '{}'", bucket, e);
        }
    }

    /**
     * Убирает разобранный файл из опрашиваемого префикса: успешный — в {@code processed/},
     * упавший — в {@code failed/}, чтобы разбор ошибки не требовал угадывать, какой из файлов
     * не прошёл. Вызывается по завершении импорта, иначе перенос выдернул бы файл из-под чтения.
     *
     * <p>Сбой переноса не эскалируется: файл остаётся на месте, повторный импорт всё равно
     * заблокирован заведённой задачей.
     */
    private void archive(UUID jobId, String objectKey) {
        String targetKey = archiveKeyFor(jobId, objectKey);
        try {
            storage.move(bucket, objectKey, targetKey);
            jobService.relocateSource(jobId, targetKey);
            log.info("Archived import file {}/{} to {}", bucket, objectKey, targetKey);
        } catch (Exception e) {
            log.error("Failed to archive import file {}/{} to {}; leaving it in place",
                    bucket, objectKey, targetKey, e);
        }
    }

    private String archiveKeyFor(UUID jobId, String objectKey) {
        ImportJobStatus status = jobService.findById(jobId).getStatus();
        String destination = status == ImportJobStatus.COMPLETED ? processedPrefix : failedPrefix;
        // Идентификатор задачи в имени связывает архивный файл с записью о прогоне и не даёт
        // повторной загрузке того же имени затереть предыдущий архив.
        return destination + jobId + "-" + fileName(objectKey);
    }

    private String fileName(String objectKey) {
        int lastSlash = objectKey.lastIndexOf('/');
        return lastSlash < 0 ? objectKey : objectKey.substring(lastSlash + 1);
    }
}
