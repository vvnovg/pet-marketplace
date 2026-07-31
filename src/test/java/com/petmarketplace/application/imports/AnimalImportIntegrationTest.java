package com.petmarketplace.application.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.petmarketplace.IntegrationTestBase;
import com.petmarketplace.domain.importjob.entity.AnimalImportJob;
import com.petmarketplace.domain.importjob.entity.ImportJobStatus;
import com.petmarketplace.domain.user.entity.Role;
import com.petmarketplace.infrastructure.storage.FileStorageService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Прогон импорта на файле в 100 000 строк: ~5% строк с ошибками формата (не проходят конвертацию
 * или Bean Validation) и ~3% строк со ссылкой на незарегистрированного владельца (отсеиваются
 * {@link OwnerValidationBatchValidator}). Остальные должны оказаться в {@code listings}.
 *
 * <p>Файл кладётся в хранилище через {@link FileStorageService} — в тестовом профиле это
 * {@code local} с базой {@code build/test-uploads}, так что путь совпадает с тем, откуда его
 * читает {@link AnimalImportService}.
 */
@Tag("integration")
class AnimalImportIntegrationTest extends IntegrationTestBase {

    private static final int TOTAL_ROWS = 100_000;
    private static final int FORMAT_ERRORS = TOTAL_ROWS * 5 / 100;
    private static final int MISSING_OWNERS = TOTAL_ROWS * 3 / 100;
    private static final int VALID_ROWS = TOTAL_ROWS - FORMAT_ERRORS - MISSING_OWNERS;

    private static final int OWNER_COUNT = 50;

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
    private static final String[] HEADERS = {
            "Кличка", "Вид", "Порода", "Возраст (мес)", "Пол",
            "Цена", "Валюта", "Город", "Email владельца",
            "Описание", "Цвет", "Вес (кг)", "Страна", "Прививки", "Документы", "Здоровье"
    };

    @Autowired
    private AnimalImportService importService;
    @Autowired
    private AnimalImportJobService jobService;
    @Autowired
    private FileStorageService storage;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Random rng = new Random(42);
    private final List<String> ownerEmails = new ArrayList<>();
    private final List<UUID> ownerIds = new ArrayList<>();

    @BeforeEach
    void createOwners() {
        for (int i = 0; i < OWNER_COUNT; i++) {
            TestUser owner = createUniqueUser(Role.SELLER);
            ownerEmails.add(owner.email());
            ownerIds.add(owner.id());
        }
    }

    @AfterEach
    void removeImportedListings() {
        // Прогон вставляет десятки тысяч объявлений в общую для всего JVM-прогона базу —
        // без уборки они замедляют и искажают последующие тестовые классы.
        if (!ownerIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM listings WHERE seller_id IN (" + placeholders() + ")",
                    ownerIds.toArray());
        }
    }

    private String placeholders() {
        return String.join(",", Collections.nCopies(ownerIds.size(), "?"));
    }

    @Test
    void shouldImport100kAnimalsWithErrors() throws Exception {
        byte[] excelBytes = generateExcel();

        String bucket = "imports";
        String objectKey = "test-animals-" + UUID.randomUUID() + ".xlsx";
        storage.store(bucket, objectKey, new ByteArrayInputStream(excelBytes), excelBytes.length,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        long listingsBefore = countOwnedListings();

        AnimalImportJob job = jobService.create(bucket, objectKey);
        importService.importAnimals(job.getId(), bucket, objectKey);

        AnimalImportJob completed = awaitCompletion(job.getId(), Duration.ofMinutes(10));

        assertThat(completed.getErrorMessage()).isNull();
        assertThat(completed.getStatus()).isEqualTo(ImportJobStatus.COMPLETED);
        assertThat(completed.getTotalRows()).isEqualTo(TOTAL_ROWS);
        assertThat(completed.getInsertedRows()).isEqualTo(VALID_ROWS);
        assertThat(completed.getRejectedRows()).isEqualTo(FORMAT_ERRORS + MISSING_OWNERS);

        assertThat(countOwnedListings() - listingsBefore).isEqualTo(VALID_ROWS);

        assertThat(completed.getReportBucket()).isNotNull();
        assertThat(completed.getReportKey()).isNotNull();
        try (var report = storage.retrieve(completed.getReportBucket(), completed.getReportKey())) {
            assertThat(report.readNBytes(4)).isNotEmpty();
        }

        storage.delete(bucket, objectKey);
        storage.delete(completed.getReportBucket(), completed.getReportKey());
    }

    private long countOwnedListings() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM listings WHERE seller_id IN (" + placeholders() + ")",
                Long.class,
                ownerIds.toArray());
        return count == null ? 0L : count;
    }

    private byte[] generateExcel() throws Exception {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            Sheet sheet = workbook.createSheet("Animals");

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS[i]);
            }

            int rowIdx = 1;
            for (int i = 0; i < TOTAL_ROWS; i++) {
                Row row = sheet.createRow(rowIdx++);
                boolean formatError = i < FORMAT_ERRORS;
                boolean missingOwner = i >= FORMAT_ERRORS && i < FORMAT_ERRORS + MISSING_OWNERS;

                if (formatError) {
                    writeFormatErrorRow(row, i);
                } else if (missingOwner) {
                    writeValidRow(row, "nonexistent" + (i - FORMAT_ERRORS) + "@example.com");
                } else {
                    writeValidRow(row, ownerEmails.get(rng.nextInt(ownerEmails.size())));
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
        row.createCell(col++).setCellValue(DOGS_CATEGORY_ID.toString());
        row.createCell(col++).setCellValue(LABRADOR_BREED_ID.toString());
        row.createCell(col++).setCellValue(rng.nextInt(1, 121));
        row.createCell(col++).setCellValue(rng.nextBoolean() ? "MALE" : "FEMALE");
        row.createCell(col++).setCellValue(rng.nextInt(500, 500_001));
        row.createCell(col++).setCellValue("RUB");
        row.createCell(col++).setCellValue(CITIES[rng.nextInt(CITIES.length)]);
        row.createCell(col++).setCellValue(ownerEmail);
        row.createCell(col++).setCellValue("Описание: " + NAMES[rng.nextInt(NAMES.length)]);
        row.createCell(col++).setCellValue(COLORS[rng.nextInt(COLORS.length)]);
        // Locale.ROOT: в ru-локали "%.1f" даёт запятую, которую конвертер BigDecimal не примет.
        row.createCell(col++).setCellValue(String.format(Locale.ROOT, "%.1f", rng.nextDouble(0.5, 80.0)));
        row.createCell(col++).setCellValue("Россия");
        row.createCell(col++).setCellValue(rng.nextBoolean() ? "TRUE" : "FALSE");
        row.createCell(col++).setCellValue(rng.nextBoolean() ? "TRUE" : "FALSE");
        row.createCell(col).setCellValue("Здоров");
    }

    /**
     * Пять видов ошибок формата, по кругу: пустая кличка, невалидный email, отрицательный
     * возраст, нечисловая цена, неизвестный пол. Заполняются только обязательные колонки —
     * необязательные для отбраковки роли не играют.
     */
    private void writeFormatErrorRow(Row row, int index) {
        int errorType = index % 5;
        String name = errorType == 0 ? "" : "Питомец" + index;
        Object age = errorType == 2 ? -5 : 12;
        String gender = errorType == 4 ? "UNKNOWN" : (errorType == 1 || errorType == 3 ? "FEMALE" : "MALE");
        Object price = errorType == 3 ? "дорого" : 10000;
        String email = errorType == 1 ? "not-an-email" : ownerEmails.get(0);

        int col = 0;
        row.createCell(col++).setCellValue(name);
        row.createCell(col++).setCellValue(DOGS_CATEGORY_ID.toString());
        row.createCell(col++).setCellValue(LABRADOR_BREED_ID.toString());
        row.createCell(col++).setCellValue(((Number) age).doubleValue());
        row.createCell(col++).setCellValue(gender);
        Cell priceCell = row.createCell(col++);
        if (price instanceof Number number) {
            priceCell.setCellValue(number.doubleValue());
        } else {
            priceCell.setCellValue((String) price);
        }
        row.createCell(col++).setCellValue("RUB");
        row.createCell(col++).setCellValue("Москва");
        row.createCell(col).setCellValue(email);
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
