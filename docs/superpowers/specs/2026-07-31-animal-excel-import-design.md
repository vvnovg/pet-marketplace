# Animal Excel Import — Design Spec

**Date:** 2026-07-31
**Status:** Approved

## Overview

Streaming import of `.xlsx` files with animals into the `listings` table, using the [excel-import](https://github.com/excelimport/excel-import) library. Files are fetched from MinIO (S3-compatible storage). Each animal must belong to an existing owner (user); records with non-existent owners are rejected with reason «владелец не зарегистрирован» and marked red in the color-coded Excel report.

## Architecture

```
Excel file in MinIO
       │
       ▼
┌──────────────────┐     ┌─────────────────────┐     ┌──────────────────┐
│  REST endpoint   │     │  @Scheduled poller  │     │  MinioFileStorage│
│  POST /imports   │     │  (imports bucket)   │     │  Service         │
└────────┬─────────┘     └──────────┬──────────┘     └────────┬─────────┘
         │                          │                         │
         ▼                          ▼                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      AnimalImportJobService                         │
│   Создаёт задачу (PENDING) → запускает @Async импорт                │
└──────────────────────────────────────┬──────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      AnimalImportService                            │
│   1. Скачивает .xlsx из MinIO (InputStream)                         │
│   2. ExcelImporter<AnimalImportRow>.importFile(inputStream)          │
│   3. Загружает цветной отчёт в MinIO                                │
│   4. Обновляет задачу (COMPLETED/FAILED)                            │
└──────────────────────────────────────┬──────────────────────────────┘
                                       │
                          ┌────────────┴────────────┐
                          ▼                         ▼
              ┌───────────────────┐    ┌──────────────────────────┐
              │ OwnerValidation   │    │  ExcelImporter            │
              │ BatchValidator    │    │  (excel-import library)   │
              │                   │    │                           │
              │ Проверяет email   │    │  SAX streaming → POJO    │
              │ владельцев в БД   │    │  Bean Validation          │
              │ Подставляет UUID  │    │  Multi-row INSERT        │
              │ в seller_id       │    │  Color-coded report      │
              └───────────────────┘    └──────────────────────────┘
```

## Components

### 1. `AnimalImportRow` — модель строки Excel

POJO с аннотациями excel-import. Маппится на таблицу `listings`.

**Обязательные колонки Excel:** Кличка, Вид (category_id), Порода (breed_id), Возраст (мес), Пол, Цена, Валюта, Город, Email владельца.

**Опциональные:** Описание, Цвет, Вес (кг), Страна, Прививки, Документы, Здоровье.

**Особенность:** `sellerEmail` — только в Excel (не мапится на БД). `sellerId` — только в БД (заполняется `BatchValidator`'ом после резолва email → UUID).

**Кастомные конвертеры:**
- `CellConverter<UUID>` — для `category_id`/`breed_id` (в Excel — UUID строкой)
- `CellConverter<ListingGender>` — для `gender` (в Excel — "MALE"/"FEMALE" строкой)
- `status` — не мапится из Excel, всегда `ACTIVE` (выставляется в `BatchValidator`)

### 2. `OwnerValidationBatchValidator`

Реализует `BatchValidator<AnimalImportRow>`. Для каждого батча одним SQL-запросом находит существующих пользователей по email. Для ненайденных возвращает `RowError` с `ErrorKind.BATCH`, кодом `OWNER_NOT_FOUND` и сообщением «владелец не зарегистрирован: <email>». Для найденных подставляет `sellerId` в строку.

### 3. `MinioFileStorageService` — реализация

Замена текущей заглушки (`UnsupportedOperationException`) на полноценную реализацию через MinIO Java SDK. Методы: `store`, `retrieve`, `delete`, `getPublicUrl`. Включается через `storage.provider=minio`.

### 4. `AnimalImportService`

Оркестрация импорта. Помечен `@Async`. Получает файл из MinIO как `InputStream`, передаёт в `ExcelImporter.importFile(inputStream, name)`, загружает отчёт в MinIO, обновляет задачу.

### 5. `AnimalImportJobService`

CRUD для `AnimalImportJob`. Методы: `create`, `markStarted`, `markCompleted`, `markFailed`, `findById`, `findRecent`.

### 6. `AnimalImportController`

REST-эндпоинты под `/api/v1/admin/imports` (только ADMIN/MODERATOR):
- `POST /animals` — создать задачу на импорт, вернуть `{ jobId, status: "PENDING" }` (202 Accepted)
- `GET /{jobId}` — статус задачи
- `GET /` — список последних задач

### 7. `AnimalImportScheduler`

`@Scheduled(fixedDelay)` — поллит бакет `imports` в MinIO, для каждого нового файла создаёт задачу и запускает импорт. Включается через `import.scheduler.enabled=true`.

### 8. `AnimalImportJob` — entity + таблица

```sql
CREATE TABLE animal_import_jobs (
    id              UUID PRIMARY KEY,
    status          VARCHAR(20) NOT NULL,
    source_bucket   VARCHAR(255) NOT NULL,
    source_key      VARCHAR(500) NOT NULL,
    total_rows      BIGINT,
    inserted_rows   BIGINT,
    rejected_rows   BIGINT,
    report_bucket   VARCHAR(255),
    report_key      VARCHAR(500),
    error_message   TEXT,
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
```

Статусы: `PENDING` → `IN_PROGRESS` → `COMPLETED` | `FAILED`.

## Dependencies

- `excel-import-spring-boot-starter` (локальный проект `~/projects/excel/`, подключается через `includeBuild` в `settings.gradle.kts`: `includeBuild("../excel")` — это подхватит оба модуля `excel-import-core` и `excel-import-spring-boot-starter`)
- MinIO Java SDK (добавить в `build.gradle.kts`)
- Существующие: Spring Boot, PostgreSQL, Liquibase, Testcontainers

## Integration Test

Расширяет `IntegrationTestBase`. Использует Testcontainers: PostgreSQL + MinIO.

**Генерация тестового Excel (100 000 строк):**
- ~92 000 валидных записей
- ~5 000 ошибок формата (невалидный email, отрицательный возраст, пустая кличка, нечисловая цена)
- ~3 000 несуществующих владельцев
- 50 пользователей (SELLER) создаются в тесте
- Виды/породы из seed-данных
- Генерация через POI `SXSSFWorkbook` (стриминговая запись)

**Проверки:**
- ~92 000 строк вставлено в `listings`
- ~8 000 строк rejected
- Все строки с несуществующими владельцами → rejected, причина «владелец не зарегистрирован»
- Строки с ошибками формата → rejected с соответствующими причинами
- Отчёт существует в MinIO
- Статус задачи = COMPLETED

## Files to Create/Modify

| File | Action |
|---|---|
| `build.gradle.kts` | Добавить MinIO SDK, excel-import зависимость |
| `settings.gradle.kts` | Добавить `includeBuild` для excel-import |
| `src/main/java/.../domain/importjob/AnimalImportJob.java` | Новый entity |
| `src/main/java/.../domain/importjob/AnimalImportJobRepository.java` | Новый repository |
| `src/main/java/.../application/imports/AnimalImportRow.java` | Модель строки |
| `src/main/java/.../application/imports/OwnerValidationBatchValidator.java` | Валидатор |
| `src/main/java/.../application/imports/AnimalImportService.java` | Сервис импорта |
| `src/main/java/.../application/imports/AnimalImportJobService.java` | Сервис задач |
| `src/main/java/.../application/imports/AnimalImportController.java` | REST |
| `src/main/java/.../application/imports/AnimalImportScheduler.java` | Шедулер |
| `src/main/java/.../application/imports/dto/*.java` | DTO |
| `src/main/java/.../infrastructure/storage/MinioFileStorageService.java` | Реализация MinIO |
| `src/main/java/.../config/MinioConfig.java` | Конфигурация MinIO |
| `src/main/resources/db/changelog/changelogs/007-animal-import-jobs.yaml` | Миграция |
| `src/main/resources/db/changelog/db.changelog-master.yaml` | Добавить 007 |
| `src/main/resources/application.yml` | Настройки MinIO, импорта |
| `src/test/java/.../application/imports/AnimalImportIntegrationTest.java` | Интеграционный тест |
