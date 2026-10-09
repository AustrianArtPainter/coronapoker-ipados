package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.table.TableSessionSummary;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import sun.misc.Unsafe;

/** Real camera actions and asynchronous completion ownership, without a GPU.
 * Unsafe is confined to this desktop probe and is not included in the app.
 */
public final class MobileScreenshotProbe {
    private static final Unsafe MEMORY;
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true); MEMORY = (Unsafe) field.get(null);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("cp-camera-probe-").toString());
        CoronaPokerGdxTable table = table();
        set(table, "finalSummary", TableSessionSummary.empty());
        require(table.handleFinalSummaryNavigationAction(4), "final camera was not handled");
        require((boolean) get(table, "screenshotRequested"), "final camera opened an empty catalog without taking a screenshot");
        require((int) get(table, "uiLayer") == 0, "catalog opened before the PNG was saved");
        System.out.println("PASS: actual final-summary camera requests render-thread capture");

        table = table(); set(table, "fastBarExpanded", true);
        float size = (float) getStatic("FAST_BUTTON_SIZE"), gap = (float) getStatic("FAST_BUTTON_GAP");
        float padding = (float) getStatic("FAST_BAR_PADDING");
        float x = (float) getStatic("FAST_BAR_X") + padding
                + CoronaPokerGdxTable.FastAccessAction.SCREENSHOTS.ordinal() * (size + gap) + size / 2;
        float y = (float) getStatic("FAST_BAR_Y") + padding + size / 2;
        require((boolean) call(table, "handleFastAccessClick", new Class<?>[]{float.class, float.class}, x, y), "fast camera was not handled");
        require((boolean) get(table, "screenshotRequested"), "fast camera did not request a screenshot");
        require(!(boolean) get(table, "fastBarExpanded"), "capture would include the expanded toolbar");
        long request = (long) get(table, "mobileScreenshotRequest");
        call(table, "mobileCaptureAndViewScreenshot", new Class<?>[]{});
        require((long) get(table, "mobileScreenshotRequest") == request, "repeat tap started a duplicate writer");
        System.out.println("PASS: actual table camera captures once and closes its toolbar");

        written(table, request, true);
        require((int) get(table, "uiLayer") == CoronaPokerGdxTable.UI_SCREENSHOTS, "successful save did not open the catalog");
        System.out.println("PASS: successful writer completion opens the catalog on its owner");

        table = requested(); request = (long) get(table, "mobileScreenshotRequest");
        written(table, request - 1, true);
        require((boolean) get(table, "mobileScreenshotViewerPending"), "stale callback cancelled the current capture");
        written(table, request, false);
        require((int) get(table, "uiLayer") == 0 && !(boolean) get(table, "mobileScreenshotViewerPending"), "failed PNG save opened the catalog or prevented retry");
        System.out.println("PASS: stale callbacks are ignored; failed writes release capture ownership");

        for (String field : new String[]{"disposed", "mobileResumeRequired", "finalExitPending"}) {
            table = requested(); request = (long) get(table, "mobileScreenshotRequest");
            set(table, field, true); written(table, request, true);
            require((int) get(table, "uiLayer") == 0, field + " allowed an old catalog to open");
            table = table(); set(table, field, true);
            call(table, "mobileCaptureAndViewScreenshot", new Class<?>[]{});
            require(!(boolean) get(table, "screenshotRequested"), field + " allowed a new capture");
        }
        System.out.println("PASS: background, disposal and final exit block capture and delayed UI callbacks");

        table = requested(); request = (long) get(table, "mobileScreenshotRequest");
        set(table, "uiLayer", CoronaPokerGdxTable.UI_GAME_LOG); written(table, request, true);
        require((int) get(table, "uiLayer") == CoronaPokerGdxTable.UI_GAME_LOG, "save callback replaced a subsequently opened surface");
        System.out.println("PASS: writer completion preserves a subsequently opened UI surface");
    }
    private static CoronaPokerGdxTable table() throws Exception {
        return (CoronaPokerGdxTable) MEMORY.allocateInstance(CoronaPokerGdxTable.class);
    }
    private static CoronaPokerGdxTable requested() throws Exception {
        CoronaPokerGdxTable table = table();
        call(table, "mobileCaptureAndViewScreenshot", new Class<?>[]{}); return table;
    }
    private static void written(Object table, long request, boolean saved) throws Exception {
        call(table, "mobileScreenshotWritten", new Class<?>[]{long.class, boolean.class}, request, saved);
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, values);
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static Object getStatic(String name) throws Exception {
        Field field = CoronaPokerGdxTable.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
