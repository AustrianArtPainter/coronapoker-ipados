package com.tonikelope.coronapoker.gdx;

import java.util.Locale;

/** Bounded pure table labels. One monitor protects all shared cache state. */
final class IOSTableUppercaseCache {
    private static final int LOCALE_CAPACITY = 8, TEXT_CAPACITY = 128;
    private static final int MAX_LANGUAGE_LENGTH = 64, MAX_TEXT_LENGTH = 160, MAX_RESULT_LENGTH = 320;
    private static final Language[] languages = new Language[LOCALE_CAPACITY];
    private static final Label[] labels = new Label[TEXT_CAPACITY];
    private static int nextLanguage, nextLabel;

    private IOSTableUppercaseCache() { }

    static synchronized String uppercase(String value, String language) {
        if (value == null) return "";
        // Preserve the original Locale.forLanguageTag(null) failure, including
        // for empty text, before consulting any cached value.
        if (language == null) return value.toUpperCase(Locale.forLanguageTag(language));
        boolean boundedLanguage = language.length() <= MAX_LANGUAGE_LENGTH;
        boolean boundedText = boundedLanguage && value.length() <= MAX_TEXT_LENGTH;
        String found = boundedText ? findLabel(language, value) : null;
        if (found != null) return found;
        Locale locale = boundedLanguage ? findLocale(language) : null;
        if (locale == null) {
            locale = Locale.forLanguageTag(language);
            if (boundedLanguage) {
                languages[nextLanguage] = new Language(language, locale);
                nextLanguage = (nextLanguage + 1) % LOCALE_CAPACITY;
            }
        }
        String result = value.toUpperCase(locale);
        if (boundedText && result.length() <= MAX_RESULT_LENGTH) {
            labels[nextLabel] = new Label(language, value, result);
            nextLabel = (nextLabel + 1) % TEXT_CAPACITY;
        }
        return result;
    }

    private static String findLabel(String language, String value) {
        for (int index = 0; index < labels.length; index++) {
            Label entry = labels[index];
            if (entry != null && entry.language.equals(language) && entry.value.equals(value)) return entry.result;
        }
        return null;
    }

    private static Locale findLocale(String language) {
        for (int index = 0; index < languages.length; index++) {
            Language entry = languages[index];
            if (entry != null && entry.language.equals(language)) return entry.locale;
        }
        return null;
    }

    private static final class Language {
        final String language;
        final Locale locale;
        Language(String language, Locale locale) { this.language = language; this.locale = locale; }
    }
    private static final class Label {
        final String language, value, result;
        Label(String language, String value, String result) {
            this.language = language; this.value = value; this.result = result;
        }
    }
}
