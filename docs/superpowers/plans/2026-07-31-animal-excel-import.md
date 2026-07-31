# Animal Excel Import — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Streaming import of `.xlsx` files with animals into the `listings` table via excel-import library, with MinIO as file source, owner validation, and color-coded Excel reports.

**Architecture:** New `imports` application module under `com.petmarketplace.application.imports`. Uses excel-import's `ExcelImporterFactory` (Spring Boot starter), a `BatchValidator` for owner resolution, and `@Async` for non-blocking import. REST endpoints under `/admin/imports`, scheduler polls MinIO bucket.

**Tech Stack:** Spring Boot 4.x, Java 26, excel-import 0.1.0-SNAPSHOT (includeBuild), MinIO Java SDK, PostgreSQL 16, Liquibase, Testcontainers, POI (for test Excel generation)

## Global Constraints

- Java 26 toolchain, Gradle 9 (system `gradle`, not `./gradlew`)
- Jackson 3 (`tools.jackson.databind.*`) — no Jackson 2 `ObjectMapper`
- Liquibase for all schema changes (`hibernate.ddl-auto=none`)
- `server.servlet.context-path: /api/v1`
- `spring.jackson.default-property-inclusion: non_null`
- Timestamps in UTC
- Business errors: `BusinessException` (409), `ValidationException` (400), `ResourceNotFoundException` (404)
- MapStruct with Spring component model (`-Amapstruct.defaultComponentModel=spring`)
- Java compile with `-parameters`
- Testcontainers for integration tests, extend `IntegrationTestBase`
- `@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")` on admin controllers

---

### Task 1: Add Gradle dependencies (excel-import + MinIO SDK)

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`

**Interfaces:**
- Produces: `includeBuild("../excel")` in settings, `implementation("org.novgorodtsev.excelimport:excel-import-spring-boot-starter:0.1.0-SNAPSHOT")` and `implementation("io.minio:minio:8.5.17")` in dependencies

- [x] **Step 1: Add includeBuild to settings.gradle.kts**

Read `settings.gradle.kts`, add `includeBuild("../excel")`:

```kotlin
rootProject.name = "pet-marketplace"

includeBuild("../excel")
```

- [x] **Step 2: Add dependencies to build.gradle.kts**

Add excel-import starter and MinIO SDK to the `dependencies` block:

```kotlin
// Excel import (streaming .xlsx → PostgreSQL)
implementation("org.novgorodtsev.excelimport:excel-import-spring-boot-starter:0.1.0-SNAPSHOT")

// MinIO S3-compatible storage client
implementation("io.minio:minio:8.5.17")
```

- [x] **Step 3: Verify build compiles**

```bash
cd ~/pet-marketplace && gradle compileJava
```

Expected: BUILD SUCCESSFUL (excel-import modules are built from source via includeBuild)

- [x] **Step 4: Commit**

```bash
git add settings.gradle.kts build.gradle.kts
git commit -m "build: add excel-import (includeBuild) and MinIO SDK dependencies"
```

---

### Task 2: MinIO configuration bean

**Files:**
- Create: `src/main/java/com/petmarketplace/config/MinioConfig.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Produces: `MinioClient` bean (from `io.minio.MinioClient`), configured from `storage.minio.*` properties

- [x] **Step 1: Create MinioConfig.java**

```java
package com.petmarketplace.config;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Bean
    @ConditionalOnProperty(name = "storage.provider", havingValue = "minio")
    @ConfigurationProperties(prefix = "storage.minio")
    public MinioProperties minioProperties() {
        return new MinioProperties();
    }

    @Bean
    @ConditionalOnProperty(name = "storage.provider", havingValue = "minio")
    public MinioClient minioClient(MinioProperties props) {
        return MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .build();
    }

    public record MinioProperties(String endpoint, String accessKey, String secretKey) {}
}
```

- [x] **Step 2: Verify application.yml has minio properties**

Check that `application.yml` already contains (it does — no change needed):

```yaml
storage:
  minio:
    endpoint: http://localhost:9000
    access-key: minioadmin
    secret-key: minioadmin
```

- [x] **Step 3: Verify build compiles**

```bash
cd ~/pet-marketplace && gradle compileJava
```

Expected: BUILD SUCCESSFUL

- [x] **Step 4: Commit**

```bash
git add src/main/java/com/petmarketplace/config/MinioConfig.java
git commit -m "feat: add MinIO client configuration bean"
```

---

### Task 3: Implement MinioFileStorageService

**Files:**
- Modify: `src/main/java/com/petmarketplace/infrastructure/storage/MinioFileStorageService.java`

**Interfaces:**
- Consumes: `MinioClient` bean from Task 2
- Produces: Working `FileStorageService` implementation (replaces `UnsupportedOperationException` stub)

- [x] **Step 1: Read current MinioFileStorageService**

```bash
cat ~/pet-marketplace/src/main/java/com/petmarketplace/infrastructure/storage/MinioFileStorageService.java
```

- [x] **Step 2: Replace with full implementation**

```java
package com.petmarketplace.infrastructure.storage;

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
    public String getPublicUrl(String bucket, String objectKey) {
        // MinIO public URL: endpoint/bucket/objectKey
        // In production this would be a CDN URL; for now return a relative path
        // that the frontend proxy resolves
        return "/api/proxy/files/" + bucket + "/" + objectKey;
    }
}
```

- [x] **Step 3: Verify build compiles**

```bash
cd ~/pet-marketplace && gradle compileJava
```

Expected: BUILD SUCCESSFUL

- [x] **Step 4: Commit**

```bash
git add src/main/java/com/petmarketplace/infrastructure/storage/MinioFileStorageService.java
git commit -m "feat: implement MinioFileStorageService with MinIO Java SDK"
```

---

### Task 4: Database migration — animal_import_jobs table

**Files:**
- Create: `src/main/resources/db/changelog/changelogs/007-animal-import-jobs.yaml`
- Modify: `src/main/resources/db/changelog/db.changelog-master.yaml`

**Interfaces:**
- Produces: `animal_import_jobs` table with columns: id (UUID PK), status, source_bucket, source_key, total_rows, inserted_rows, rejected_rows, report_bucket, report_key, error_message, started_at, finished_at, created_at

- [x] **Step 1: Create Liquibase changeset**

```yaml
databaseChangeLog:
  - logicalFilePath: db/changelog/changelogs/007-animal-import-jobs.yaml

  - changeSet:
      id: 007-create-table-animal-import-jobs
      author: liquibase
      changes:
        - createTable:
            tableName: animal_import_jobs
            remarks: Tracks Excel import jobs — source file, progress, and report location
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: status
                  type: VARCHAR(20)
                  remarks: PENDING, IN_PROGRESS, COMPLETED, FAILED
                  constraints:
                    nullable: false
              - column:
                  name: source_bucket
                  type: VARCHAR(255)
                  constraints:
                    nullable: false
              - column:
                  name: source_key
                  type: VARCHAR(500)
                  constraints:
                    nullable: false
              - column:
                  name: total_rows
                  type: BIGINT
              - column:
                  name: inserted_rows
                  type: BIGINT
              - column:
                  name: rejected_rows
                  type: BIGINT
              - column:
                  name: report_bucket
                  type: VARCHAR(255)
              - column:
                  name: report_key
                  type: VARCHAR(500)
              - column:
                  name: error_message
                  type: TEXT
              - column:
                  name: started_at
                  type: TIMESTAMP
              - column:
                  name: finished_at
                  type: TIMESTAMP
              - column:
                  name: created_at
                  type: TIMESTAMP
                  defaultValueComputed: now()
                  constraints:
                    nullable: false
```

- [x] **Step 2: Register in master changelog**

Add after the 006 entry in `db.changelog-master.yaml`:

```yaml
  - include:
      file: changelogs/007-animal-import-jobs.yaml
      relativeToChangelogFile: true
```

- [x] **Step 3: Verify migration runs**

```bash
cd ~/pet-marketplace && docker-compose up -d postgres && gradle bootRun
# Check logs for successful Liquibase migration
# Ctrl+C to stop
```

Expected: Liquibase runs 007 changeset without errors.

- [x] **Step 4: Commit**

```bash
git add src/main/resources/db/changelog/changelogs/007-animal-import-jobs.yaml \
        src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat: add animal_import_jobs table migration"
```

---

### Task 5: AnimalImportJob entity and repository

**Files:**
- Create: `src/main/java/com/petmarketplace/domain/importjob/entity/AnimalImportJob.java`
- Create: `src/main/java/com/petmarketplace/domain/importjob/entity/ImportJobStatus.java`
- Create: `src/main/java/com/petmarketplace/domain/importjob/repository/AnimalImportJobRepository.java`

**Interfaces:**
- Produces: `AnimalImportJob` entity (extends `BaseEntity`), `ImportJobStatus` enum, `AnimalImportJobRepository` (extends `JpaRepository<AnimalImportJob, UUID>`)

- [x] **Step 1: Create ImportJobStatus enum**

```java
package com.petmarketplace.domain.importjob.entity;

public enum ImportJobStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
```

- [x] **Step 2: Create AnimalImportJob entity**

```java
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
```

- [x] **Step 3: Create AnimalImportJobRepository**

```java
package com.petmarketplace.domain.importjob.repository;

import com.petmarketplace.domain.importjob.entity.AnimalImportJob;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnimalImportJobRepository extends JpaRepository<AnimalImportJob, UUID> {

    List<AnimalImportJob> findTop20ByOrderByCreatedAtDesc();
}
```

- [x] **Step 4: Verify build compiles**

```bash
cd ~/pet-marketplace && gradle compileJava
```

Expected: BUILD SUCCESSFUL

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/petmarketplace/domain/importjob/
git commit -m "feat: add AnimalImportJob entity, status enum, and repository"
```

---

### Task 6: AnimalImportRow model with custom converters

**Files:**
- Create: `src/main/java/com/petmarketplace/application/imports/AnimalImportRow.java`
- Create: `src/main/java/com/petmarketplace/application/imports/convert/UuidCellConverter.java`
- Create: `src/main/java/com/petmarketplace/application/imports/convert/GenderCellConverter.java`

**Interfaces:**
- Consumes: excel-import annotations (`@ExcelSheet`, `@ExcelColumn`, `@Column`, `@TargetTable`), Jakarta Validation
- Produces: `AnimalImportRow` POJO, `CellConverter<UUID>`, `CellConverter<ListingGender>`

- [x] **Step 1: Create UuidCellConverter**

```java
package com.petmarketplace.application.imports.convert;

import java.util.UUID;
import org.novgorodtsev.excelimport.convert.CellConverter;
import org.novgorodtsev.excelimport.convert.CellValue;
import org.novgorodtsev.excelimport.convert.ConversionContext;

public class UuidCellConverter implements CellConverter<UUID> {

    @Override
    public UUID convert(CellValue cell, ConversionContext ctx) {
        String text = cell.text().strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new org.novgorodtsev.excelimport.convert.ConversionException(
                    "не является UUID: " + text);
        }
    }
}
```

- [x] **Step 2: Create GenderCellConverter**

```java
package com.petmarketplace.application.imports.convert;

import com.petmarketplace.domain.listing.entity.ListingGender;
import org.novgorodtsev.excelimport.convert.CellConverter;
import org.novgorodtsev.excelimport.convert.CellValue;
import org.novgorodtsev.excelimport.convert.ConversionContext;
import org.novgorodtsev.excelimport.convert.ConversionException;

public class GenderCellConverter implements CellConverter<ListingGender> {

    @Override
    public ListingGender convert(CellValue cell, ConversionContext ctx) {
        String text = cell.text().strip().toUpperCase();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return ListingGender.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new ConversionException(
                    "ожидается MALE или FEMALE, получено: " + text);
        }
    }
}
```

- [x] **Step 3: Create AnimalImportRow**

```java
package com.petmarketplace.application.imports;

import com.petmarketplace.domain.listing.entity.ListingGender;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.novgorodtsev.excelimport.annotation.Column;
import org.novgorodtsev.excelimport.annotation.ExcelColumn;
import org.novgorodtsev.excelimport.annotation.ExcelSheet;
import org.novgorodtsev.excelimport.annotation.TargetTable;

@ExcelSheet(name = "Animals", headerRow = 0)
@TargetTable(schema = "public", name = "listings")
@Getter
@Setter
public class AnimalImportRow {

    // --- Обязательные колонки Excel ---

    @ExcelColumn(header = "Кличка")
    @Column("title")
    @NotBlank
    private String title;

    @ExcelColumn(header = "Вид")
    @Column("category_id")
    @NotNull
    private UUID categoryId;

    @ExcelColumn(header = "Порода", required = false)
    @Column("breed_id")
    private UUID breedId;

    @ExcelColumn(header = "Возраст (мес)")
    @Column("age_months")
    @NotNull
    @Min(0)
    private Integer ageMonths;

    @ExcelColumn(header = "Пол")
    @Column("gender")
    @NotNull
    private ListingGender gender;

    @ExcelColumn(header = "Цена")
    @Column("price")
    @NotNull
    @DecimalMin("0.01")
    private BigDecimal price;

    @ExcelColumn(header = "Валюта")
    @Column("currency")
    @NotBlank
    @Size(max = 3)
    private String currency;

    @ExcelColumn(header = "Город")
    @Column("location_city")
    @NotBlank
    private String locationCity;

    // Только в Excel — не мапится на БД. Обрабатывается в OwnerValidationBatchValidator
    @ExcelColumn(header = "Email владельца")
    @NotBlank
    @Email
    private String sellerEmail;

    // Только в БД — заполняется BatchValidator'ом после резолва email → UUID
    @Column("seller_id")
    private UUID sellerId;

    // --- Опциональные колонки ---

    @ExcelColumn(header = "Описание", required = false)
    @Column("description")
    private String description;

    @ExcelColumn(header = "Цвет", required = false)
    @Column("color")
    private String color;

    @ExcelColumn(header = "Вес (кг)", required = false)
    @Column("weight_kg")
    private BigDecimal weightKg;

    @ExcelColumn(header = "Страна", required = false)
    @Column("location_country")
    private String locationCountry;

    @ExcelColumn(header = "Прививки", required = false)
    @Column("has_vaccination")
    private Boolean hasVaccination;

    @ExcelColumn(header = "Документы", required = false)
    @Column("has_documents")
    private Boolean hasDocuments;

    @ExcelColumn(header = "Здоровье", required = false)
    @Column("health_info")
    private String healthInfo;

    // Не из Excel — всегда ACTIVE
    @Column("status")
    @Builder.Default
    private String status = "ACTIVE";
}
```

- [x] **Step 4: Verify build compiles**

```bash
cd ~/pet-marketplace && gradle compileJava
```

Expected: BUILD SUCCESSFUL

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/petmarketplace/application/imports/
git commit -m "feat: add AnimalImportRow model with UUID and Gender cell converters"
```

---

### Task 7: OwnerValidationBatchValidator

**Files:**
- Create: `src/main/java/com/petmarketplace/application/imports/OwnerValidationBatchValidator.java`
- Modify: `src/main/java/com/petmarketplace/domain/user/repository/UserRepository.java`

**Interfaces:**
- Consumes: `UserRepository` (needs `findAllByEmailIn` method), `BatchValidator<AnimalImportRow>` interface
- Produces: `OwnerValidationBatchValidator` — Spring `@Component`, resolves `sellerEmail` → `sellerId` (UUID), returns `RowError` with `ErrorKind.BATCH` and message "владелец не зарегистрирован: <email>" for missing owners

- [x] **Step 1: Add findAllByEmailIn to UserRepository**

```java
// Add to UserRepository interface:
List<User> findAllByEmailIn(Collection<String> emails);
```

Full import needed: `import java.util.Collection;` and `import java.util.List;`

- [x] **Step 2: Create OwnerValidationBatchValidator**

```java
package com.petmarketplace.application.imports;

import com.petmarketplace.domain.user.entity.User;
import com.petmarketplace.domain.user.repository.UserRepository;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.novgorodtsev.excelimport.ErrorKind;
import org.novgorodtsev.excelimport.RowError;
import org.novgorodtsev.excelimport.RowRef;
import org.novgorodtsev.excelimport.validate.BatchValidator;
import org.springframework.stereotype.Component;

@Component
public class OwnerValidationBatchValidator implements BatchValidator<AnimalImportRow> {

    private final UserRepository userRepository;

    public OwnerValidationBatchValidator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public List<RowError> validate(List<RowRef<AnimalImportRow>> batch, Connection connection) {
        // 1. Собираем все email из батча
        Set<String> emails = batch.stream()
                .map(ref -> ref.value().getSellerEmail().toLowerCase())
                .collect(Collectors.toSet());

        // 2. Одним запросом находим существующих пользователей
        Map<String, UUID> existing = userRepository.findAllByEmailIn(emails).stream()
                .collect(Collectors.toMap(
                        u -> u.getEmail().toLowerCase(),
                        User::getId));

        // 3. Для каждой строки: если email не найден — ошибка, иначе подставляем seller_id
        List<RowError> errors = new ArrayList<>();
        for (RowRef<AnimalImportRow> ref : batch) {
            String email = ref.value().getSellerEmail().toLowerCase();
            UUID ownerId = existing.get(email);
            if (ownerId == null) {
                errors.add(new RowError(
                        ref.rowNum(),
                        "Email владельца",
                        ref.value().getSellerEmail(),
                        ErrorKind.BATCH,
                        "OWNER_NOT_FOUND",
                        "владелец не зарегистрирован: " + email));
            } else {
                ref.value().setSellerId(ownerId);
            }
        }
        return errors;
    }
}
```

- [x] **Step 3: Verify build compiles**

```bash
cd ~/pet-marketplace && gradle compileJava
```

Expected: BUILD SUCCESSFUL

- [x] **Step 4: Commit**

```bash
git add src/main/java/com/petmarketplace/application/imports/OwnerValidationBatchValidator.java \
        src/main/java/com/petmarketplace/domain/user/repository/UserRepository.java
git commit -m "feat: add OwnerValidationBatchValidator — resolves seller email to UUID"
```

---

### Task 8: AnimalImportJobService

**Files:**
- Create: `src/main/java/com/petmarketplace/application/imports/AnimalImportJobService.java`

**Interfaces:**
- Consumes: `AnimalImportJobRepository`
- Produces: `AnimalImportJobService` with methods: `create(bucket, key) → AnimalImportJob`, `markStarted(id) → AnimalImportJob`, `markCompleted(id, report, reportBucket, reportKey)`, `markFailed(id, errorMessage)`, `findById(id) → AnimalImportJob`, `findRecent() → List<AnimalImportJob>`

- [x] **Step 1: Create AnimalImportJobService**

```java
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
                .id(UUID.randomUUID())
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

    @Transactional(readOnly = true)
    public List<AnimalImportJob> findRecent() {
        return repository.findTop20ByOrderByCreatedAtDesc();
    }
}
```

- [x] **Step 2: Verify build compiles**

```bash
cd ~/pet-marketplace && gradle compileJava
```

Expected: BUILD SUCCESSFUL

- [x] **Step 3: Commit**

```bash
git add src/main/java/com/petmarketplace/application/imports/AnimalImportJobService.java
git commit -m "feat: add AnimalImportJobService — CRUD for import jobs"
```

---

### Task 9: AnimalImportService — async import orchestration

**Files:**
- Create: `src/main/java/com/petmarketplace/application/imports/AnimalImportService.java`

**Interfaces:**
- Consumes: `ExcelImporterFactory`, `FileStorageService`, `AnimalImportJobService`, `OwnerValidationBatchValidator`, custom converters
- Produces: `AnimalImportService.importAnimals(jobId, bucket, objectKey)` — `@Async`, downloads from MinIO, runs ExcelImporter, uploads report, updates job

- [x] **Step 1: Create AnimalImportService**

```java
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
import org.novgorodtsev.excelimport.ExcelImporter;
import org.novgorodtsev.excelimport.ImportConfig;
import org.novgorodtsev.excelimport.ImportReport;
import org.novgorodtsev.excelimport.spring.ExcelImporterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AnimalImportService {

    private static final Logger log = LoggerFactory.getLogger(AnimalImportService.class);

    private final ExcelImporterFactory importerFactory;
    private final FileStorageService storage;
    private final AnimalImportJobService jobService;

    public AnimalImportService(ExcelImporterFactory importerFactory,
                               FileStorageService storage,
                               AnimalImportJobService jobService) {
        this.importerFactory = importerFactory;
        this.storage = storage;
        this.jobService = jobService;
    }

    @Async("importTaskExecutor")
    public void importAnimals(UUID jobId, String bucket, String objectKey) {
        AnimalImportJob job = jobService.markStarted(jobId);
        log.info("Starting animal import job {}: {}/{}", jobId, bucket, objectKey);

        try (InputStream in = storage.retrieve(bucket, objectKey)) {
            ImportConfig config = ImportConfig.builder()
                    .batchSize(1000)
                    .reportPath(Path.of(System.getProperty("java.io.tmpdir"), "report-" + jobId + ".xlsx"))
                    .build();

            ExcelImporter<AnimalImportRow> importer = ExcelImporter.builder(AnimalImportRow.class)
                    .dataSource(null) // will be injected by factory... 
                    // Actually use the factory which has DataSource and validators wired
                    .config(config)
                    .build();

            // We need to use the factory to get DataSource + validators.
            // Let's use the factory approach instead:
            // (The factory already wires DataSource, validators, converters)
        } catch (Exception e) {
            log.error("Import job {} failed", jobId, e);
            jobService.markFailed(jobId, e.getMessage());
        }
    }
}
```

Wait — the factory approach is better. Let me rewrite this properly. The `ExcelImporterFactory.create(type, config)` wires DataSource, validators, converters, etc. But we also need to register custom converters. The factory supports `CellConverter` beans via `ObjectProvider`, but we registered them manually. Let me use the builder directly with the factory's DataSource.

Actually, looking at the factory code more carefully:

```java
public <T> ExcelImporter<T> create(Class<T> type, ImportConfig config) {
    ExcelImporter.Builder<T> builder = ExcelImporter.builder(type)
            .dataSource(dataSource)
            .config(config);
    for (BatchValidator<T> validator : batchValidatorsFor(type)) {
        builder.batchValidator(validator);
    }
    converters.forEach(builder::converter);
    ...
    return builder.build();
}
```

The factory has `converters` map which is empty by default (from `ExcelImportAutoConfiguration`). We need to register our custom converters. The cleanest way: use the factory but also register converters on the builder. Since the factory returns a builder, we can't modify it after `build()`. 

Better approach: inject `DataSource` directly and use `ExcelImporter.builder()` with all the wiring done manually. This gives us full control.

Let me rewrite the service properly.

- [x] **Step 1 (revised): Create AnimalImportService**

```java
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
```

- [x] **Step 2: Add importTaskExecutor to AsyncConfig**

Modify `src/main/java/com/petmarketplace/config/AsyncConfig.java` — add a second executor bean for imports:

```java
@Bean(name = "importTaskExecutor")
public Executor importTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(10);
    executor.setThreadNamePrefix("import-");
    executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
}
```

- [x] **Step 3: Verify build compiles**

```bash
cd ~/pet-marketplace && gradle compileJava
```

Expected: BUILD SUCCESSFUL

- [x] **Step 4: Commit**

```bash
git add src/main/java/com/petmarketplace/application/imports/AnimalImportService.java \
        src/main/java/com/petmarketplace/config/AsyncConfig.java
git commit -m "feat: add AnimalImportService — async import orchestration"
```

---

### Task 10: DTOs for import API

**Files:**
- Create: `src/main/java/com/petmarketplace/application/imports/dto/AnimalImportRequest.java`
- Create: `src/main/java/com/petmarketplace/application/imports/dto/AnimalImportJobResponse.java`

**Interfaces:**
- Produces: `AnimalImportRequest` (record: bucket, objectKey), `AnimalImportJobResponse` (record: id, status, sourceBucket, sourceKey, totalRows, insertedRows, rejectedRows, reportUrl, errorMessage, startedAt, finishedAt, createdAt)

- [x] **Step 1: Create AnimalImportRequest**

```java
package com.petmarketplace.application.imports.dto;

import jakarta.validation.constraints.NotBlank;

public record AnimalImportRequest(
        @NotBlank String bucket,
        @NotBlank String objectKey) {
}
```

- [x] **Step 2: Create AnimalImportJobResponse**

```java
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
```

- [x] **Step 3: Verify build compiles**

```bash
cd ~/pet-marketplace && gradle compileJava
```

Expected: BUILD SUCCESSFUL

- [x] **Step 4: Commit**

```bash
git add src/main/java/com/petmarketplace/application/imports/dto/
git commit -m "feat: add import API DTOs — AnimalImportRequest and AnimalImportJobResponse"
```

---

### Task 11: AnimalImportController — REST endpoints

**Files:**
- Create: `src/main/java/com/petmarketplace/application/imports/AnimalImportController.java`

**Interfaces:**
- Consumes: `AnimalImportService`, `AnimalImportJobService`
- Produces: `POST /api/v1/admin/imports/animals` (202), `GET /api/v1/admin/imports/{jobId}`, `GET /api/v1/admin/imports`

- [x] **Step 1: Create AnimalImportController**

```java
package com.petmarketplace.application.imports;

import com.petmarketplace.application.imports.dto.AnimalImportJobResponse;
import com.petmarketplace.application.imports.dto.AnimalImportRequest;
import com.petmarketplace.domain.importjob.entity.AnimalImportJob;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/imports")
@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
public class AnimalImportController {

    private final AnimalImportService importService;
    private final AnimalImportJobService jobService;

    public AnimalImportController(AnimalImportService importService,
                                  AnimalImportJobService jobService) {
        this.importService = importService;
        this.jobService = jobService;
    }

    @PostMapping("/animals")
    public ResponseEntity<AnimalImportJobResponse> importAnimals(
            @Valid @RequestBody AnimalImportRequest request) {
        AnimalImportJob job = jobService.create(request.bucket(), request.objectKey());
        importService.importAnimals(job.getId(), request.bucket(), request.objectKey());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AnimalImportJobResponse.from(job));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<AnimalImportJobResponse> getJob(@PathVariable UUID jobId) {
        AnimalImportJob job = jobService.findById(jobId);
        return ResponseEntity.ok(AnimalImportJobResponse.from(job));
    }

    @GetMapping
    public ResponseEntity<List<AnimalImportJobResponse>> listJobs() {
        List<AnimalImportJobResponse> jobs = jobService.findRecent().stream()
                .map(AnimalImportJobResponse::from)
                .toList();
        return ResponseEntity.ok(jobs);
    }
}
```

- [x] **Step 2: Verify build compiles**

```bash
cd ~/pet-marketplace && gradle compileJava
```

Expected: BUILD SUCCESSFUL

- [x] **Step 3: Commit**

```bash
git add src/main/java/com/petmarketplace/application/imports/AnimalImportController.java
git commit -m "feat: add AnimalImportController — REST endpoints for animal import"
```

---

### Task 12: AnimalImportScheduler — MinIO bucket polling

**Files:**
- Create: `src/main/java/com/petmarketplace/application/imports/AnimalImportScheduler.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: `MinioClient` (or `FileStorageService`), `AnimalImportJobService`, `AnimalImportService`
- Produces: `@Scheduled` method that lists objects in the `imports` bucket and triggers import for new files

- [x] **Step 1: Add scheduler configuration to application.yml**

Add after the `storage:` block:

```yaml
import:
  scheduler:
    enabled: true
    poll-interval-ms: 60000
    bucket: imports
    prefix: pending/
```

- [x] **Step 2: Create AnimalImportScheduler**

```java
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
                Item item = result.get();
                if (item.isDir() || item.objectName().equals(prefix)) {
                    continue;
                }
                String objectKey = item.objectName();
                log.info("Found new import file: {}/{}", bucket, objectKey);
                AnimalImportJob job = jobService.create(bucket, objectKey);
                importService.importAnimals(job.getId(), bucket, objectKey);
            }
        } catch (Exception e) {
            log.error("Error polling import bucket", e);
        }
    }
}
```

- [x] **Step 3: Verify build compiles**

```bash
cd ~/pet-marketplace && gradle compileJava
```

Expected: BUILD SUCCESSFUL

- [x] **Step 4: Commit**

```bash
git add src/main/java/com/petmarketplace/application/imports/AnimalImportScheduler.java \
        src/main/resources/application.yml
git commit -m "feat: add AnimalImportScheduler — polls MinIO bucket for new import files"
```

---

### Task 13: Integration test — 100K Excel generation + import

**Files:**
- Create: `src/test/java/com/petmarketplace/application/imports/AnimalImportIntegrationTest.java`

**Interfaces:**
- Consumes: `IntegrationTestBase`, Testcontainers MinIO, POI `SXSSFWorkbook`
- Produces: Integration test that generates 100K Excel records, uploads to MinIO, triggers import, verifies results

- [x] **Step 1: Create AnimalImportIntegrationTest**

```java
package com.petmarketplace.application.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.petmarketplace.IntegrationTestBase;
import com.petmarketplace.domain.category.entity.Breed;
import com.petmarketplace.domain.category.entity.Category;
import com.petmarketplace.domain.category.repository.BreedRepository;
import com.petmarketplace.domain.category.repository.CategoryRepository;
import com.petmarketplace.domain.importjob.entity.AnimalImportJob;
import com.petmarketplace.domain.importjob.entity.ImportJobStatus;
import com.petmarketplace.domain.importjob.repository.AnimalImportJobRepository;
import com.petmarketplace.domain.listing.entity.ListingGender;
import com.petmarketplace.domain.listing.repository.ListingRepository;
import com.petmarketplace.domain.user.entity.Role;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("integration")
class AnimalImportIntegrationTest extends IntegrationTestBase {

    private static final int TOTAL_ROWS = 100_000;
    private static final int FORMAT_ERRORS = 5_000;   // ~5%
    private static final int MISSING_OWNERS = 3_000;  // ~3%
    private static final int VALID_ROWS = TOTAL_ROWS - FORMAT_ERRORS - MISSING_OWNERS;

    private static final String[] CITIES = {
            "Москва", "Санкт-Петербург", "Казань", "Новосибирск",
            "Екатеринбург", "Нижний Новгород", "Самара", "Краснодар"
    };
    private static final String[] COLORS = {
            "Чёрный", "Белый", "Рыжий", "Серый", "Коричневый", "Пятнистый"
    };
    private static final String[] NAMES = {
            "Барсик", "Мурка", "Рекс", "Джек", "Лайма", "Бобик", "Шарик",
            "Тузик", "Граф", "Лорд", "Чарли", "Макс", "Люси", "Белла",
            "Дейзи", "Рокки", "Оскар", "Арчи", "Тоби", "Зевс"
    };

    @Autowired
    private AnimalImportService importService;
    @Autowired
    private AnimalImportJobService jobService;
    @Autowired
    private AnimalImportJobRepository jobRepository;
    @Autowired
    private ListingRepository listingRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private BreedRepository breedRepository;

    private final Random rng = new Random(42);
    private final List<TestUser> owners = new ArrayList<>();
    private Category dogsCategory;
    private Breed labradorBreed;

    @BeforeEach
    void setUp() {
        // Create 50 seller users (owners)
        for (int i = 0; i < 50; i++) {
            owners.add(createUniqueUser(Role.SELLER));
        }
        dogsCategory = categoryRepository.findById(DOGS_CATEGORY_ID).orElseThrow();
        labradorBreed = breedRepository.findById(LABRADOR_BREED_ID).orElseThrow();
    }

    @Test
    void shouldImport100kAnimalsWithErrors() throws Exception {
        // 1. Generate Excel with 100K rows
        byte[] excelBytes = generateExcel();

        // 2. Upload to MinIO (or local storage in test mode)
        String bucket = "imports";
        String objectKey = "test-animals-" + UUID.randomUUID() + ".xlsx";

        // In test profile, storage.provider=local, so we use local storage
        // Create the import job and trigger import
        AnimalImportJob job = jobService.create(bucket, objectKey);

        // Store the file locally (since test uses local storage)
        java.nio.file.Path uploadPath = java.nio.file.Path.of("build/test-uploads", bucket, objectKey);
        java.nio.file.Files.createDirectories(uploadPath.getParent());
        java.nio.file.Files.write(uploadPath, excelBytes);

        // 3. Trigger import
        importService.importAnimals(job.getId(), bucket, objectKey);

        // 4. Wait for completion (poll with timeout)
        AnimalImportJob completed = awaitCompletion(job.getId(), Duration.ofMinutes(5));

        // 5. Verify
        assertThat(completed.getStatus()).isEqualTo(ImportJobStatus.COMPLETED);
        assertThat(completed.getTotalRows()).isEqualTo(TOTAL_ROWS);
        assertThat(completed.getInsertedRows()).isEqualTo(VALID_ROWS);
        assertThat(completed.getRejectedRows()).isEqualTo(FORMAT_ERRORS + MISSING_OWNERS);

        // Verify listings were inserted
        long listingCount = listingRepository.count();
        assertThat(listingCount).isEqualTo(VALID_ROWS);

        // Verify report exists
        assertThat(completed.getReportBucket()).isNotNull();
        assertThat(completed.getReportKey()).isNotNull();
    }

    private byte[] generateExcel() throws Exception {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Animals");

            // Header row
            Row header = sheet.createRow(0);
            String[] headers = {
                    "Кличка", "Вид", "Порода", "Возраст (мес)", "Пол",
                    "Цена", "Валюта", "Город", "Email владельца",
                    "Описание", "Цвет", "Вес (кг)", "Страна", "Прививки", "Документы", "Здоровье"
            };
            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
            }

            // Data rows
            int rowIdx = 1;
            List<String> missingOwnerEmails = generateMissingOwnerEmails();

            for (int i = 0; i < TOTAL_ROWS; i++) {
                Row row = sheet.createRow(rowIdx++);
                boolean isFormatError = i < FORMAT_ERRORS;
                boolean isMissingOwner = i >= FORMAT_ERRORS && i < FORMAT_ERRORS + MISSING_OWNERS;

                if (isFormatError) {
                    writeFormatErrorRow(row, i);
                } else {
                    writeValidRow(row, isMissingOwner ? missingOwnerEmails.get(i - FORMAT_ERRORS)
                            : owners.get(rng.nextInt(owners.size())).email());
                }
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            workbook.dispose();
            return bos.toByteArray();
        }
    }

    private void writeValidRow(Row row, String ownerEmail) {
        int col = 0;
        row.createCell(col++).setCellValue(NAMES[rng.nextInt(NAMES.length)]);
        row.createCell(col++).setCellValue(dogsCategory.getId().toString());
        row.createCell(col++).setCellValue(labradorBreed.getId().toString());
        row.createCell(col++).setCellValue(rng.nextInt(1, 121));  // 1-120 months
        row.createCell(col++).setCellValue(rng.nextBoolean() ? "MALE" : "FEMALE");
        row.createCell(col++).setCellValue(rng.nextInt(500, 500_001));  // 500-500000 RUB
        row.createCell(col++).setCellValue("RUB");
        row.createCell(col++).setCellValue(CITIES[rng.nextInt(CITIES.length)]);
        row.createCell(col++).setCellValue(ownerEmail);
        row.createCell(col++).setCellValue("Описание: " + NAMES[rng.nextInt(NAMES.length)]);
        row.createCell(col++).setCellValue(COLORS[rng.nextInt(COLORS.length)]);
        row.createCell(col++).setCellValue(String.format("%.1f", rng.nextDouble(0.5, 80.0)));
        row.createCell(col++).setCellValue("Россия");
        row.createCell(col++).setCellValue(rng.nextBoolean() ? "TRUE" : "FALSE");
        row.createCell(col++).setCellValue(rng.nextBoolean() ? "TRUE" : "FALSE");
        row.createCell(col++).setCellValue("Здоров");
    }

    private void writeFormatErrorRow(Row row, int index) {
        int errorType = index % 5;
        int col = 0;
        switch (errorType) {
            case 0 -> { // Empty name
                row.createCell(col++).setCellValue("");
                row.createCell(col++).setCellValue(dogsCategory.getId().toString());
                row.createCell(col++).setCellValue(labradorBreed.getId().toString());
                row.createCell(col++).setCellValue(12);
                row.createCell(col++).setCellValue("MALE");
                row.createCell(col++).setCellValue(10000);
                row.createCell(col++).setCellValue("RUB");
                row.createCell(col++).setCellValue("Москва");
                row.createCell(col++).setCellValue(owners.get(0).email());
            }
            case 1 -> { // Invalid email
                row.createCell(col++).setCellValue("Питомец" + index);
                row.createCell(col++).setCellValue(dogsCategory.getId().toString());
                row.createCell(col++).setCellValue(labradorBreed.getId().toString());
                row.createCell(col++).setCellValue(12);
                row.createCell(col++).setCellValue("FEMALE");
                row.createCell(col++).setCellValue(10000);
                row.createCell(col++).setCellValue("RUB");
                row.createCell(col++).setCellValue("Москва");
                row.createCell(col++).setCellValue("not-an-email");
            }
            case 2 -> { // Negative age
                row.createCell(col++).setCellValue("Питомец" + index);
                row.createCell(col++).setCellValue(dogsCategory.getId().toString());
                row.createCell(col++).setCellValue(labradorBreed.getId().toString());
                row.createCell(col++).setCellValue(-5);
                row.createCell(col++).setCellValue("MALE");
                row.createCell(col++).setCellValue(10000);
                row.createCell(col++).setCellValue("RUB");
                row.createCell(col++).setCellValue("Москва");
                row.createCell(col++).setCellValue(owners.get(0).email());
            }
            case 3 -> { // Non-numeric price
                row.createCell(col++).setCellValue("Питомец" + index);
                row.createCell(col++).setCellValue(dogsCategory.getId().toString());
                row.createCell(col++).setCellValue(labradorBreed.getId().toString());
                row.createCell(col++).setCellValue(12);
                row.createCell(col++).setCellValue("FEMALE");
                row.createCell(col++).setCellValue("дорого");
                row.createCell(col++).setCellValue("RUB");
                row.createCell(col++).setCellValue("Москва");
                row.createCell(col++).setCellValue(owners.get(0).email());
            }
            case 4 -> { // Invalid gender
                row.createCell(col++).setCellValue("Питомец" + index);
                row.createCell(col++).setCellValue(dogsCategory.getId().toString());
                row.createCell(col++).setCellValue(labradorBreed.getId().toString());
                row.createCell(col++).setCellValue(12);
                row.createCell(col++).setCellValue("UNKNOWN");
                row.createCell(col++).setCellValue(10000);
                row.createCell(col++).setCellValue("RUB");
                row.createCell(col++).setCellValue("Москва");
                row.createCell(col++).setCellValue(owners.get(0).email());
            }
        }
    }

    private List<String> generateMissingOwnerEmails() {
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < MISSING_OWNERS; i++) {
            emails.add("nonexistent" + i + "@example.com");
        }
        return emails;
    }

    private AnimalImportJob awaitCompletion(UUID jobId, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            AnimalImportJob job = jobService.findById(jobId);
            if (job.getStatus() == ImportJobStatus.COMPLETED
                    || job.getStatus() == ImportJobStatus.FAILED) {
                return job;
            }
            TimeUnit.SECONDS.sleep(2);
        }
        throw new AssertionError("Import job " + jobId + " did not complete within " + timeout);
    }
}
```

- [x] **Step 2: Run the integration test**

```bash
cd ~/pet-marketplace && gradle test --tests "com.petmarketplace.application.imports.AnimalImportIntegrationTest" -x test
```

Wait — need to run just this test. Use the correct Gradle syntax:

```bash
cd ~/pet-marketplace && gradle test --tests "com.petmarketplace.application.imports.AnimalImportIntegrationTest"
```

Expected: Test passes — 100K rows generated, ~92K inserted, ~8K rejected, report exists.

- [x] **Step 3: Commit**

```bash
git add src/test/java/com/petmarketplace/application/imports/AnimalImportIntegrationTest.java
git commit -m "test: add integration test — 100K animal Excel import with format errors and missing owners"
```

---

## Self-Review

**1. Spec coverage:**
- AnimalImportRow model → Task 6
- OwnerValidationBatchValidator → Task 7
- MinioFileStorageService → Task 3
- AnimalImportService → Task 9
- AnimalImportJobService → Task 8
- AnimalImportController → Task 11
- AnimalImportScheduler → Task 12
- animal_import_jobs table → Task 4
- AnimalImportJob entity → Task 5
- DTOs → Task 10
- Integration test (100K) → Task 13
- Gradle dependencies → Task 1
- MinIO config → Task 2
- Async executor → Task 9 (Step 2)

**2. Placeholder scan:** No TBD, TODO, or vague instructions. All code is concrete.

**3. Type consistency:**
- `AnimalImportRow.sellerId` (UUID) set by `OwnerValidationBatchValidator` → consumed by excel-import's `RowBinder` via `@Column("seller_id")` ✓
- `AnimalImportJobResponse.from(AnimalImportJob)` → used in `AnimalImportController` ✓
- `AnimalImportRequest(bucket, objectKey)` → used in `AnimalImportController.importAnimals()` ✓
- `ImportJobStatus` enum values match DB column and entity ✓

---

## Отклонения при выполнении

Интеграционный тест (Task 13) выявил три дефекта модели `AnimalImportRow`, не видимые
на этапе компиляции — все три проявляются только при вставке в реальную БД:

1. **`gender` как Java-enum.** pgjdbc не выводит SQL-тип для enum-константы
   (`Can't infer the SQL type ...`) и прерывает весь прогон. Поле стало `String`,
   `GenderCellConverter` теперь `CellConverter<String>` и возвращает имя константы
   `ListingGender`; проверка обязательна — CHECK-ограничения на `listings.gender` нет.
2. **Отсутствие `id`.** `listings.id` — NOT NULL без DEFAULT, а библиотека вставляет
   ровно перечисленные в модели колонки и ключей не генерирует. Добавлено db-only поле
   `@Column("id") private UUID id = UUID.randomUUID()` (модель инстанцируется на каждую
   строку, поэтому UUID у каждой строки свой).
3. **`sellerEmail` → несуществующая колонка `seller_email`.** В библиотеке *каждое*
   поле с `@ExcelColumn`/`@Column` попадало в `INSERT`, а имя выводилось `NamingStrategy`.
   По решению владельца доработана сама библиотека: `@ExcelColumn(insertable = false)`
   читает и валидирует колонку, но исключает её из вставки
   (`/Users/vvnovg/projects/excel`, коммит `d6cf0ab`, README обновлён).

Дополнительно: `settings.gradle.kts` искал `includeBuild("../projects/excel")` по
фиксированному относительному пути и не разрешался из git-worktree — путь теперь ищется
вверх по предкам корня, с переопределением через `-PexcelImportPath`.
