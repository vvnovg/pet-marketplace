package com.petmarketplace.application.imports.dto;

import com.petmarketplace.domain.importjob.entity.AnimalImportJob;
import com.petmarketplace.domain.importjob.entity.ImportJobStatus;
import java.time.Instant;
import java.util.UUID;

public record AnimalImportJobResponse(
        UUID id,
        ImportJobStatus status,
        String sourceBucket,
        String sourceKey,
        Long totalRows,
        Long insertedRows,
        Long rejectedRows,
        String reportUrl,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt) {

    public static AnimalImportJobResponse from(AnimalImportJob job) {
        String reportUrl = null;
        if (job.getReportBucket() != null && job.getReportKey() != null) {
            reportUrl = "/api/proxy/files/" + job.getReportBucket() + "/" + job.getReportKey();
        }
        return new AnimalImportJobResponse(
                job.getId(),
                job.getStatus(),
                job.getSourceBucket(),
                job.getSourceKey(),
                job.getTotalRows(),
                job.getInsertedRows(),
                job.getRejectedRows(),
                reportUrl,
                job.getErrorMessage(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getCreatedAt());
    }
}
