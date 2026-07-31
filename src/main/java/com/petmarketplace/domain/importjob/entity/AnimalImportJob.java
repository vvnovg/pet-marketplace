package com.petmarketplace.domain.importjob.entity;

import com.petmarketplace.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "animal_import_jobs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimalImportJob extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ImportJobStatus status;

    @Column(name = "source_bucket", nullable = false, length = 255)
    private String sourceBucket;

    @Column(name = "source_key", nullable = false, length = 500)
    private String sourceKey;

    @Column(name = "total_rows")
    private Long totalRows;

    @Column(name = "inserted_rows")
    private Long insertedRows;

    @Column(name = "rejected_rows")
    private Long rejectedRows;

    @Column(name = "report_bucket", length = 255)
    private String reportBucket;

    @Column(name = "report_key", length = 500)
    private String reportKey;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
