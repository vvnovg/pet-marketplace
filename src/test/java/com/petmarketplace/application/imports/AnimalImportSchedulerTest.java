package com.petmarketplace.application.imports;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petmarketplace.domain.importjob.entity.AnimalImportJob;
import com.petmarketplace.domain.importjob.entity.ImportJobStatus;
import com.petmarketplace.infrastructure.storage.FileStorageService;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AnimalImportSchedulerTest {

    private static final String BUCKET = "imports";
    private static final String PREFIX = "pending/";
    private static final String OBJECT_KEY = PREFIX + "animals.xlsx";

    private static final String PROCESSED_PREFIX = "processed/";
    private static final String FAILED_PREFIX = "failed/";

    private final MinioClient minioClient = mock(MinioClient.class);
    private final AnimalImportJobService jobService = mock(AnimalImportJobService.class);
    private final AnimalImportService importService = mock(AnimalImportService.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final AnimalImportScheduler scheduler =
            new AnimalImportScheduler(minioClient, jobService, importService, storage);

    @BeforeEach
    void injectProperties() {
        ReflectionTestUtils.setField(scheduler, "bucket", BUCKET);
        ReflectionTestUtils.setField(scheduler, "prefix", PREFIX);
        ReflectionTestUtils.setField(scheduler, "processedPrefix", PROCESSED_PREFIX);
        ReflectionTestUtils.setField(scheduler, "failedPrefix", FAILED_PREFIX);
    }

    @Test
    void shouldStartImportForANewFile() {
        listing(OBJECT_KEY);
        AnimalImportJob job = dispatchableJob(ImportJobStatus.COMPLETED);

        scheduler.pollImportBucket();

        verify(importService).importAnimals(job.getId(), BUCKET, OBJECT_KEY);
    }

    @Test
    void shouldArchiveASuccessfulFileUnderTheProcessedPrefix() {
        listing(OBJECT_KEY);
        AnimalImportJob job = dispatchableJob(ImportJobStatus.COMPLETED);

        scheduler.pollImportBucket();

        String expectedKey = PROCESSED_PREFIX + job.getId() + "-animals.xlsx";
        verify(storage).move(BUCKET, OBJECT_KEY, expectedKey);
        verify(jobService).relocateSource(job.getId(), expectedKey);
    }

    @Test
    void shouldArchiveAFailedFileUnderTheFailedPrefix() {
        listing(OBJECT_KEY);
        AnimalImportJob job = dispatchableJob(ImportJobStatus.FAILED);

        scheduler.pollImportBucket();

        verify(storage).move(BUCKET, OBJECT_KEY, FAILED_PREFIX + job.getId() + "-animals.xlsx");
    }

    /** Не сумев убрать файл, шедулер не должен и переписывать путь в задаче. */
    @Test
    void shouldLeaveTheJobPointingAtTheSourceWhenArchivingFails() {
        listing(OBJECT_KEY);
        AnimalImportJob job = dispatchableJob(ImportJobStatus.COMPLETED);
        doThrow(new IllegalStateException("storage unavailable"))
                .when(storage).move(any(), any(), any());

        scheduler.pollImportBucket();

        verify(jobService, never()).relocateSource(any(), any());
    }

    /**
     * Ключевая регрессия: импорт не удаляет и не перемещает файл, поэтому он попадает в листинг
     * на каждом опросе. Без проверки «задача уже заведена» каждый опрос дублировал бы все
     * объявления из файла.
     */
    @Test
    void shouldNotReimportAFileThatAlreadyHasAJob() {
        listing(OBJECT_KEY);
        when(jobService.alreadyPickedUp(BUCKET, OBJECT_KEY)).thenReturn(true);

        scheduler.pollImportBucket();

        verify(jobService, never()).create(any(), any());
        verify(importService, never()).importAnimals(any(), any(), any());
    }

    @Test
    void shouldSkipDirectoriesAndThePrefixPlaceholder() {
        Item directory = mock(Item.class);
        when(directory.isDir()).thenReturn(true);
        Item placeholder = mock(Item.class);
        when(placeholder.objectName()).thenReturn(PREFIX);
        listing(directory, placeholder);

        scheduler.pollImportBucket();

        verify(jobService, never()).create(any(), any());
        verify(importService, never()).importAnimals(any(), any(), any());
    }

    /** Сбой на одном файле не должен уносить с собой остальные — их разбор продолжается. */
    @Test
    void shouldKeepGoingAfterOneFileFails() {
        Result<Item> broken = resultThrowing();
        Item healthy = mock(Item.class);
        when(healthy.objectName()).thenReturn(OBJECT_KEY);
        listing(broken, okResult(healthy));
        AnimalImportJob job = dispatchableJob(ImportJobStatus.COMPLETED);

        scheduler.pollImportBucket();

        verify(importService).importAnimals(job.getId(), BUCKET, OBJECT_KEY);
    }

    // --- helpers ---

    /** Файл ещё не разбирался, импорт отрабатывает синхронно и заканчивается статусом {@code status}. */
    private AnimalImportJob dispatchableJob(ImportJobStatus status) {
        UUID jobId = UUID.randomUUID();
        AnimalImportJob job = mock(AnimalImportJob.class);
        when(job.getId()).thenReturn(jobId);
        when(job.getStatus()).thenReturn(status);
        when(jobService.alreadyPickedUp(BUCKET, OBJECT_KEY)).thenReturn(false);
        when(jobService.create(BUCKET, OBJECT_KEY)).thenReturn(job);
        when(jobService.findById(jobId)).thenReturn(job);
        when(importService.importAnimals(jobId, BUCKET, OBJECT_KEY))
                .thenReturn(CompletableFuture.completedFuture(null));
        return job;
    }

    private void listing(String... objectNames) {
        Item[] items = new Item[objectNames.length];
        for (int i = 0; i < objectNames.length; i++) {
            Item item = mock(Item.class);
            when(item.objectName()).thenReturn(objectNames[i]);
            items[i] = item;
        }
        listing(items);
    }

    private void listing(Item... items) {
        Result<Item>[] results = new Result[items.length];
        for (int i = 0; i < items.length; i++) {
            results[i] = okResult(items[i]);
        }
        listing(results);
    }

    @SafeVarargs
    private void listing(Result<Item>... results) {
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(results));
    }

    private Result<Item> okResult(Item item) {
        @SuppressWarnings("unchecked")
        Result<Item> result = mock(Result.class);
        try {
            when(result.get()).thenReturn(item);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return result;
    }

    private Result<Item> resultThrowing() {
        @SuppressWarnings("unchecked")
        Result<Item> result = mock(Result.class);
        try {
            when(result.get()).thenThrow(new IllegalStateException("corrupt listing entry"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return result;
    }
}
