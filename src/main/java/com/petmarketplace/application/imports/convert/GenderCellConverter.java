package com.petmarketplace.application.imports.convert;

import com.petmarketplace.domain.listing.entity.ListingGender;
import org.novgorodtsev.excelimport.convert.CellConverter;
import org.novgorodtsev.excelimport.convert.CellValue;
import org.novgorodtsev.excelimport.convert.ConversionContext;
import org.novgorodtsev.excelimport.convert.ConversionException;

/**
 * Разбирает колонку «Пол» в имя константы {@link ListingGender}, а не в саму константу:
 * значения строки биндятся в {@code PreparedStatement} через {@code setObject}, а pgjdbc не
 * умеет выводить SQL-тип для Java-enum ("Can't infer the SQL type ..."). В БД колонка
 * {@code listings.gender} — VARCHAR, поэтому в модель кладём уже готовую строку.
 *
 * <p>Проверка через {@code valueOf} обязательна: без неё в VARCHAR-колонку прошло бы любое
 * значение из файла (CHECK-ограничения на ней нет).
 */
public class GenderCellConverter implements CellConverter<String> {

    @Override
    public String convert(CellValue cell, ConversionContext ctx) {
        String text = cell.asString().strip().toUpperCase();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return ListingGender.valueOf(text).name();
        } catch (IllegalArgumentException e) {
            throw new ConversionException(
                    "INVALID_GENDER", "ожидается MALE или FEMALE, получено: " + text);
        }
    }
}
