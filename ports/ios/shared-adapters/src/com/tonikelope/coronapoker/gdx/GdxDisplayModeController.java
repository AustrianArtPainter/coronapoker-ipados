package com.tonikelope.coronapoker.gdx;
import java.util.Properties;

/** iOS owns its native window size; the existing viewport follows that size. */
final class GdxDisplayModeController {
    static boolean isFullscreenLike() { return true; }
    static boolean requiresIndependentWakeLock(boolean fullscreen) { return true; }
    static boolean toggle(Properties properties) { return false; }
    static boolean apply(GdxWindowMode mode) { return false; }
    static boolean apply(Properties properties, GdxWindowMode mode) { return false; }
    static GdxWindowMode activeMode() { return GdxWindowMode.EXCLUSIVE; }
    static void synchronizePreference(Properties properties, GdxWindowMode mode) { GdxWindowMode.persist(properties, mode); }
    static void rememberFullscreenMode(GdxWindowMode mode) {}
    static GdxWindowMode preferredFullscreenMode() { return GdxWindowMode.EXCLUSIVE; }
    static GdxWindowMode toggleTarget(GdxWindowMode active, GdxWindowMode preferred) { return GdxWindowMode.EXCLUSIVE; }
    static GdxWindowMode fullscreenPreference(GdxWindowMode configured, GdxWindowMode remembered) { return GdxWindowMode.EXCLUSIVE; }
    private GdxDisplayModeController() {}
}
