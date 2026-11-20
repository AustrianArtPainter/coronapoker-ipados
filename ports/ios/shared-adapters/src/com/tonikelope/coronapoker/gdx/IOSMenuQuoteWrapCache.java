package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import java.util.List;
import java.util.Objects;

/** Screen-owned cache for the unchanged menu quote wrapping algorithm. */
final class IOSMenuQuoteWrapCache {
    // Explicit 0 retains the original wrapping path for a rollback.
    static final boolean ENABLED = !"0".equals(System.getenv("CP_IOS_QUOTE_WRAP_CACHE"));

    private BitmapFont font;
    private BitmapFont.BitmapFontData data;
    private int scaleXBits, scaleYBits, widthBits, maxLines;
    private GdxAdaptiveFontQuality.Profile profile;
    private String language, quote;
    private List<String> lines;

    // A hit allocates neither a key nor a callback. The frontend alone owns
    // the returned wrap result and never mutates it after wrapping completes.
    List<String> get(BitmapFont currentFont, GdxAdaptiveFontQuality.Profile currentProfile,
            String currentLanguage, String currentQuote, float width, int lineLimit) {
        BitmapFont.BitmapFontData currentData = currentFont.getData();
        return lines != null && font == currentFont && data == currentData
                && scaleXBits == Float.floatToIntBits(currentData.scaleX)
                && scaleYBits == Float.floatToIntBits(currentData.scaleY)
                && widthBits == Float.floatToIntBits(width) && maxLines == lineLimit
                && Objects.equals(profile, currentProfile)
                && Objects.equals(language, currentLanguage)
                && Objects.equals(quote, currentQuote) ? lines : null;
    }

    void put(BitmapFont currentFont, GdxAdaptiveFontQuality.Profile currentProfile,
            String currentLanguage, String currentQuote, float width, int lineLimit,
            List<String> wrappedLines) {
        font = currentFont;
        data = currentFont.getData();
        scaleXBits = Float.floatToIntBits(data.scaleX);
        scaleYBits = Float.floatToIntBits(data.scaleY);
        widthBits = Float.floatToIntBits(width);
        maxLines = lineLimit;
        profile = currentProfile;
        language = currentLanguage;
        quote = currentQuote;
        lines = Objects.requireNonNull(wrappedLines, "wrappedLines");
    }

    void clear() {
        font = null;
        data = null;
        profile = null;
        language = null;
        quote = null;
        lines = null;
    }
}
