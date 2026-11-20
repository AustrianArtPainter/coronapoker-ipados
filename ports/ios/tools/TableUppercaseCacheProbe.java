package com.tonikelope.coronapoker.gdx;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Calls the production cache and the generated table methods against Java's original expression. */
public final class TableUppercaseCacheProbe {
    private static int checks;
    private static volatile String blackhole;
    private static final String[] LANGUAGES = {"en", "es", "tr", "tr-TR", "az", "lt", "el", "de",
            "und", "", "xx", "i-klingon", "de-DE-u-co-phonebk", "invalid_tag"};
    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static String original(String value, String language) {
        return value == null ? "" : value.toUpperCase(Locale.forLanguageTag(language));
    }
    private static void compare(String value, String language) {
        String actual = IOSTableUppercaseCache.uppercase(value, language);
        require(original(value, language).equals(actual), "Uppercase differs from original Java expression");
    }
    private static Object field(Object owner, String name) throws Exception {
        Class<?> type = owner instanceof Class<?> ? (Class<?>) owner : owner.getClass();
        Field field = type.getDeclaredField(name); field.setAccessible(true);
        return field.get(owner instanceof Class<?> ? null : owner);
    }
    private static void bounds() throws Exception {
        Object[] languages = (Object[]) field(IOSTableUppercaseCache.class, "languages");
        Object[] labels = (Object[]) field(IOSTableUppercaseCache.class, "labels");
        require(languages.length == 8 && labels.length == 128, "Fixed storage capacity");
        for (Object entry : languages) if (entry != null)
            require(((String) field(entry, "language")).length() <= 64, "Long language retained");
        for (Object entry : labels) if (entry != null) {
            require(((String) field(entry, "language")).length() <= 64, "Long label language retained");
            require(((String) field(entry, "value")).length() <= 160, "Long input text retained");
            require(((String) field(entry, "result")).length() <= 320, "Long result retained");
        }
    }
    private static void nullsAndGeneratedMethods() {
        require("".equals(IOSTableUppercaseCache.uppercase(null, null)), "Null input bypasses null tag");
        for (String value : new String[]{"", "i"}) {
            boolean failed = false;
            try { IOSTableUppercaseCache.uppercase(value, null); }
            catch (NullPointerException expected) { failed = true; }
            require(failed, "Non-null input must evaluate null language");
        }
        require("".equals(TableUppercaseGeneratedSeam.withText(null, null)), "Static generated null bypasses text.language");
        require("".equals(new TableUppercaseGeneratedSeam(null).instance(null)), "Instance generated null bypasses gameText.language");
        boolean failed = false;
        try { TableUppercaseGeneratedSeam.withText("", null); }
        catch (NullPointerException expected) { failed = true; }
        require(failed, "Generated empty input still evaluates text.language");
        GdxGameText text = new GdxGameText("en");
        TableUppercaseGeneratedSeam table = new TableUppercaseGeneratedSeam(text);
        for (String language : new String[]{"en", "tr", "es", "az", "lt", "de", "en", "tr"}) {
            text.setLanguage(language);
            for (String value : new String[]{null, "", "iıİI", "Straße", "σςΣ"}) {
                String expected = original(value, text.language());
                require(expected.equals(table.instance(value)), "Generated instance language switch");
                require(expected.equals(TableUppercaseGeneratedSeam.withText(value, text)), "Generated static language switch");
            }
        }
    }
    private static void oracleAndEviction() throws Exception {
        String[] texts = {null, "", "plain label", "ALREADY UPPER", "iıİI", "Straße ﬀ ﬁ ﬃ ﬄ",
                "σςΣ ὀδυσσεύς", "i\u0307\u0301 j\u0300", "\u0390\u03B0", "ქართული հայերեն",
                "中文游戏 🎴", "\uD801\uDC28\uD801\uDC29", "\uD800x\uDC00", "\r\n\t", "café mañana"};
        for (String language : LANGUAGES) for (String value : texts) compare(value, language);
        Random random = new Random(39182471L);
        for (int index = 0; index < 3000; index++) {
            char[] value = new char[random.nextInt(80)];
            for (int ch = 0; ch < value.length; ch++) value[ch] = (char) random.nextInt(65536);
            compare(new String(value), LANGUAGES[index % LANGUAGES.length]);
        }
        for (int index = 0; index < 1024; index++) compare("label i " + index, LANGUAGES[index % LANGUAGES.length]);
        compare("iıİI", "en"); compare("iıİI", "tr"); compare("iıİI", "en");
        String reused = IOSTableUppercaseCache.uppercase("cache result i", "tr");
        require(reused == IOSTableUppercaseCache.uppercase(new String("cache result i"), new String("tr")),
                "Equal full inputs reuse the immutable result");
        compare("i".repeat(160), "en"); compare("i".repeat(161), "en");
        compare("i".repeat(10000), "tr"); compare("\u0390".repeat(160), "el");
        compare("i", "en-x-" + "abcdefgh-".repeat(12));
        bounds();
    }
    private static void concurrentTables() throws Exception {
        int workers = 8, iterations = 4000;
        CountDownLatch ready = new CountDownLatch(workers), start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[workers];
        for (int worker = 0; worker < workers; worker++) {
            final int id = worker;
            threads[worker] = new Thread(() -> {
                try {
                    ready.countDown(); start.await();
                    for (int index = 0; index < iterations; index++) {
                        String language = LANGUAGES[(index + id) % LANGUAGES.length];
                        String value = "iıİI Straße σς " + ((index * 17 + id) % 193);
                        if (!original(value, language).equals(IOSTableUppercaseCache.uppercase(value, language)))
                            throw new AssertionError("Concurrent table key/result mismatch");
                    }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            }, "uppercase-table-" + worker);
            threads[worker].start();
        }
        ready.await(); start.countDown();
        for (Thread thread : threads) thread.join();
        require(failure.get() == null, "Concurrent language/value/eviction checks failed: " + failure.get());
        checks += workers * iterations;
        bounds();
    }
    private static long steadyAllocations() {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        require(bean.isThreadAllocatedMemorySupported(), "Host allocation counter available");
        bean.setThreadAllocatedMemoryEnabled(true);
        String[] values = new String[32];
        for (int i = 0; i < values.length; i++) values[i] = "steady iı label " + i;
        for (int round = 0; round < 3; round++) for (int i = 0; i < 100000; i++)
            blackhole = IOSTableUppercaseCache.uppercase(values[i & 31], (i & 32) == 0 ? "en" : "tr");
        long thread = Thread.currentThread().getId(), allocated = 0;
        // Warm the measured method and counter boundary as well as the cache;
        // class/JIT setup is not a steady hit allocation.
        for (int round = 0; round < 5; round++) {
            long bytes = measuredHits(bean, thread, values);
            if (round >= 3) {
                require(bytes == 0, "Steady production helper allocated " + bytes + " bytes");
                allocated += bytes;
            }
        }
        return allocated;
    }
    private static long measuredHits(ThreadMXBean bean, long thread, String[] values) {
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 100000; i++)
            blackhole = IOSTableUppercaseCache.uppercase(values[i & 31], (i & 32) == 0 ? "en" : "tr");
        return bean.getThreadAllocatedBytes(thread) - before;
    }
    public static void main(String[] args) throws Exception {
        nullsAndGeneratedMethods(); oracleAndEviction(); concurrentTables();
        long bytes = steadyAllocations();
        System.out.println("{\"passed\":true,\"checks\":" + checks + ",\"concurrentComparisons\":32000,"
                + "\"steadyMeasuredCalls\":200000,\"steadyAllocatedBytes\":" + bytes
                + ",\"localeCapacity\":8,\"textCapacity\":128}");
    }
}
