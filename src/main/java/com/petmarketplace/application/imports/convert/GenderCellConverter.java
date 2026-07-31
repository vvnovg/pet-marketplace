package com.petmarketplace.application.imports.convert;

import com.petmarketplace.domain.listing.entity.ListingGender;
import org.novgorodtsev.excelimport.convert.CellConverter;
import org.novgorodtsev.excelimport.convert.CellValue;
import org.novgorodtsev.excelimport.convert.ConversionContext;
import org.novgorodtsev.excelimport.convert.ConversionException;

public class GenderCellConverter implements CellConverter<ListingGender> {

    @Override
    public ListingGender convert(CellValue cell, ConversionContext ctx) {
        String text = cell.asString().strip().toUpperCase();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return ListingGender.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new ConversionException(
                    "INVALID_GENDER", "ожидается MALE или FEMALE, получено: " + text);
        }
    }
}
