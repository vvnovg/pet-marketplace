package com.petmarketplace.infrastructure.localization;

import java.util.Locale;

public final class LocalizedNameResolver {

    private static final Locale RUSSIAN = Locale.of("ru");
    private static final Locale ENGLISH = Locale.of("en");

    private LocalizedNameResolver() {
    }

    public static String resolve(String nameRu, String nameEn, Locale locale) {
        if (locale != null
                && ENGLISH.getLanguage().equalsIgnoreCase(locale.getLanguage())
                && nameEn != null
                && !nameEn.isBlank()) {
            return nameEn;
        }
        return nameRu;
    }

    public static Locale resolveLocale(String languageTag) {
        if (languageTag == null || languageTag.isBlank()) {
            return RUSSIAN;
        }

        String primary = languageTag.trim().split("[,;]")[0].trim();
        Locale locale = Locale.forLanguageTag(primary);
        if (ENGLISH.getLanguage().equalsIgnoreCase(locale.getLanguage())) {
            return locale;
        }
        return RUSSIAN;
    }
}
