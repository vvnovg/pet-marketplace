package com.petmarketplace.application.imports.convert;

import java.util.UUID;
import org.novgorodtsev.excelimport.convert.CellConverter;
import org.novgorodtsev.excelimport.convert.CellValue;
import org.novgorodtsev.excelimport.convert.ConversionContext;

public class UuidCellConverter implements CellConverter<UUID> {

    @Override
    public UUID convert(CellValue cell, ConversionContext ctx) {
        String text = cell.asString().strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new org.novgorodtsev.excelimport.convert.ConversionException(
                    "INVALID_UUID", "не является UUID: " + text);
        }
    }
}
