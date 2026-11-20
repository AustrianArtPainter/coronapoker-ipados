package com.tonikelope.coronapoker.gdx;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Bounded, single-frontend text/key storage; never owns fonts or input hits. */
final class IOSFrontendMenuCache {
    private static final int CAPACITY = 16;
    private final HoverKey[] hoverKeys = new HoverKey[CAPACITY];
    private final Uppercase[] uppercase = new Uppercase[CAPACITY];
    private int hoverCursor, uppercaseCursor;
    private String language;
    private Locale locale;
    private String updateLanguage, updateVersion, updateLabel;

    String uppercase(String currentLanguage, String value, boolean retainResult) {
        Locale currentLocale = locale(currentLanguage);
        if (value == null) return "";
        if (!retainResult) return value.toUpperCase(currentLocale);
        for (Uppercase entry : uppercase) {
            if (entry != null && entry.value.equals(value)) return entry.result;
        }
        String result = value.toUpperCase(currentLocale);
        uppercase[uppercaseCursor] = new Uppercase(value, result);
        uppercaseCursor = (uppercaseCursor + 1) & (CAPACITY - 1);
        return result;
    }

    private Locale locale(String currentLanguage) {
        if (locale == null || !Objects.equals(language, currentLanguage)) {
            locale = Locale.forLanguageTag(currentLanguage);
            language = currentLanguage;
            Arrays.fill(uppercase, null);
            uppercaseCursor = 0;
        }
        return locale;
    }

    String updateLabel(GdxGameText text, String version) {
        String currentLanguage = text.language();
        if (updateLabel == null || !Objects.equals(updateLanguage, currentLanguage)
                || !Objects.equals(updateVersion, version)) {
            updateLabel = uppercase(currentLanguage,
                    text.translate("gdx.update.menu_action", version), false);
            updateLanguage = currentLanguage;
            updateVersion = version;
        }
        return updateLabel;
    }

    String hoverKey(String label, float x, float y, float width, float height) {
        int xb = Float.floatToIntBits(x), yb = Float.floatToIntBits(y);
        int wb = Float.floatToIntBits(width), hb = Float.floatToIntBits(height);
        for (HoverKey entry : hoverKeys) {
            if (entry != null && entry.xb == xb && entry.yb == yb
                    && entry.wb == wb && entry.hb == hb && Objects.equals(entry.label, label))
                return entry.key;
        }
        // Preserve the original formatter exactly on every cache miss.
        String key = GdxFrontendScreen.buttonHoverKey(label, x, y, width, height);
        hoverKeys[hoverCursor] = new HoverKey(label, xb, yb, wb, hb, key);
        hoverCursor = (hoverCursor + 1) & (CAPACITY - 1);
        return key;
    }

    void clear() {
        Arrays.fill(hoverKeys, null);
        Arrays.fill(uppercase, null);
        hoverCursor = uppercaseCursor = 0;
        language = updateLanguage = updateVersion = updateLabel = null;
        locale = null;
    }

    private static final class HoverKey {
        final String label, key;
        final int xb, yb, wb, hb;
        HoverKey(String label, int xb, int yb, int wb, int hb, String key) {
            this.label = label; this.xb = xb; this.yb = yb;
            this.wb = wb; this.hb = hb; this.key = key;
        }
    }

    private static final class Uppercase {
        final String value, result;
        Uppercase(String value, String result) { this.value = value; this.result = result; }
    }
}
