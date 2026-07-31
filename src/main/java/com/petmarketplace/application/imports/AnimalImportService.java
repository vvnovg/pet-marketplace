package com.petmarketplace.application.imports;

import com.petmarketplace.application.imports.convert.GenderCellConverter;
import com.petmarketplace.application.imports.convert.UuidCellConverter;
import com.petmarketplace.domain.importjob.entity.AnimalImportJob;
import com.petmarketplace.domain.listing.entity.ListingGender;
import com.petmarketplace.infrastructure.storage.FileStorageService;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.novgorodtsev.excelimport.ExcelImporter;
import org.novgorodtsev.excelimport.ImportConfig;
import org.novgorodtsev.excelimport.ImportReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AnimalImportService {

    private static final Logger log = LoggerFactory.getLogger(AnimalImportService.class);

    private final DataSource dataSource;
    private final FileStorageService storage;
    private final AnimalImportJobService jobService;
    private final OwnerValidationBatchValidator ownerValidator;

    public AnimalImportService(DataSource dataSource,
                               FileStorageService storage,
                               AnimalImportJobService jobService,
                               OwnerValidationBatchValidator ownerValidator) {
        this.dataSource = dataSource;
        this.storage = storage;
        this.jobService = jobService;
        this.ownerValidator = ownerValidator;
    }

    @Async("importTaskExecutor")
    public void importAnimals(UUID jobId, String bucket, String objectKey) {
        AnimalImportJob job = jobService.markStarted(jobId);
        log.info("Starting animal import job {}: {}/{}", jobId, bucket, objectKey);

        Path reportPath = Path.of(System.getProperty("java.io.tmpdir"), "report-" + jobId + ".xlsx");
        try (InputStream in = storage.retrieve(bucket, objectKey)) {
            ImportConfig config = ImportConfig.builder()
                    .batchSize(1000)
                    .reportPath(reportPath)
                    .build();

            ExcelImporter<AnimalImportRow> importer = ExcelImporter
                    .builder(AnimalImportRow.class)
                    .dataSource(dataSource)
                    .config(config)
                    .converter(UUID.class, new UuidCellConverter())
                    .converter(ListingGender.class, new GenderCellConverter())
                    .batchValidator(ownerValidator)
                    .build();

            ImportReport report = importer.importFile(in, objectKey);

            // Upload report to MinIO
            String reportBucket = "reports";
            String reportKey = "imports/" + jobId + ".xlsx";
            if (report.reportPath() != null && Files.exists(report.reportPath())) {
                try (FileInputStream fis = new FileInputStream(report.reportPath().toFile())) {
                    storage.store(reportBucket, reportKey, fis,
                            Files.size(report.reportPath()),
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                }
                Files.deleteIfExists(report.reportPath());
            }

            jobService.markCompleted(jobId, report, reportBucket, reportKey);
            log.info("Import job {} completed: {} inserted, {} rejected",
                    jobId, report.insertedRows(), report.rejectedRows());

        } catch (Exception e) {
            log.error("Import job {} failed", jobId, e);
            jobService.markFailed(jobId, e.getMessage());
            try {
                Files.deleteIfExists(reportPath);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }
}
