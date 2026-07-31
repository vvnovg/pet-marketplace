package com.petmarketplace.application.imports;

import com.petmarketplace.application.imports.convert.GenderCellConverter;
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

    // Не из Excel. listings.id — NOT NULL без DEFAULT, а библиотека вставляет ровно те колонки,
    // что есть в модели, и ключи не генерирует. Модель инстанцируется заново на каждую строку
    // (MappingModel#newInstance), поэтому инициализатор поля даёт свой UUID каждой строке.
    @Column("id")
    private UUID id = UUID.randomUUID();

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

    // Строка, а не ListingGender: pgjdbc не выводит SQL-тип для Java-enum. Значение
    // валидируется по ListingGender в GenderCellConverter.
    @ExcelColumn(header = "Пол", converter = GenderCellConverter.class)
    @Column("gender")
    @NotNull
    private String gender;

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
    private String status = "ACTIVE";
}
