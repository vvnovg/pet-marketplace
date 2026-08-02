package com.petmarketplace.application.imports;

import com.petmarketplace.domain.importjob.entity.AnimalImportJob;
import com.petmarketplace.domain.importjob.entity.ImportJobStatus;
import com.petmarketplace.domain.importjob.repository.AnimalImportJobRepository;
import com.petmarketplace.exception.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.novgorodtsev.excelimport.ImportReport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AnimalImportJobService {

    private final AnimalImportJobRepository repository;

    public AnimalImportJobService(AnimalImportJobRepository repository) {
        this.repository = repository;
    }

    public AnimalImportJob create(String sourceBucket, String sourceKey) {
        AnimalImportJob job = AnimalImportJob.builder()
                .status(ImportJobStatus.PENDING)
                .sourceBucket(sourceBucket)
                .sourceKey(sourceKey)
                .createdAt(Instant.now())
                .build();
        return repository.save(job);
    }

    public AnimalImportJob markStarted(UUID jobId) {
        AnimalImportJob job = findById(jobId);
        job.setStatus(ImportJobStatus.IN_PROGRESS);
        job.setStartedAt(Instant.now());
        return repository.save(job);
    }

    public AnimalImportJob markCompleted(UUID jobId, ImportReport report,
                                          String reportBucket, String reportKey) {
        AnimalImportJob job = findById(jobId);
        job.setStatus(ImportJobStatus.COMPLETED);
        job.setTotalRows(report.totalRows());
        job.setInsertedRows(report.insertedRows());
        job.setRejectedRows(report.rejectedRows());
        job.setReportBucket(reportBucket);
        job.setReportKey(reportKey);
        job.setFinishedAt(Instant.now());
        return repository.save(job);
    }

    public AnimalImportJob markFailed(UUID jobId, String errorMessage) {
        AnimalImportJob job = findById(jobId);
        job.setStatus(ImportJobStatus.FAILED);
        job.setErrorMessage(errorMessage);
        job.setFinishedAt(Instant.now());
        return repository.save(job);
    }

    @Transactional(readOnly = true)
    public AnimalImportJob findById(UUID jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Import job not found: " + jobId));
    }

    /**
     * Записывает новое расположение исходного файла после того, как шедулер убрал его из
     * опрашиваемого префикса, — чтобы запись о задаче указывала туда, где файл лежит на самом
     * деле. Побочный эффект намеренный: старый ключ перестаёт считаться разобранным, поэтому
     * файл с тем же именем, загруженный заново, будет импортирован как новый.
     */
    public AnimalImportJob relocateSource(UUID jobId, String sourceKey) {
        AnimalImportJob job = findById(jobId);
        job.setSourceKey(sourceKey);
        return repository.save(job);
    }

    /**
     * Заводился ли уже импорт этого объекта хранилища — независимо от того, чем он кончился.
     * Шедулер опрашивает бакет по расписанию и должен отличать новый файл от уже разобранного;
     * сам файл при этом остаётся на месте и служит исходником для повторного разбора вручную.
     */
    @Transactional(readOnly = true)
    public boolean alreadyPickedUp(String sourceBucket, String sourceKey) {
        return repository.existsBySourceBucketAndSourceKey(sourceBucket, sourceKey);
    }

    @Transactional(readOnly = true)
    public List<AnimalImportJob> findRecent() {
        return repository.findTop20ByOrderByCreatedAtDesc();
    }
}
