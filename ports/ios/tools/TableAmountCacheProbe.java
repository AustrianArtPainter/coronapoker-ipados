package com.tonikelope.coronapoker.gdx;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the production helper and mechanically generated original methods. */
public final class TableAmountCacheProbe {
    private static int checks;
    private static volatile String blackhole;

    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void compare(double amount) {
        if (!Double.isFinite(amount)) {
            RuntimeException expected = failure(amount, false), actual = failure(amount, true);
            require(expected.getClass() == actual.getClass()
                    && expected.getMessage().equals(actual.getMessage()), "Non-finite edge differs");
            return;
        }
        String expected = OriginalTableAmounts.formatAmount(amount);
        String actual = CoronaPokerGdxTable.formatAmount(amount);
        require(expected.equals(actual), "Amount differs for raw bits "
                + Long.toHexString(Double.doubleToRawLongBits(amount)));
    }

    private static Object field(String name) throws Exception {
        Field field = IOSTableAmountCache.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static boolean retained(double amount) throws Exception {
        long key = Double.doubleToRawLongBits(amount);
        long[] keys = (long[]) field("keys");
        String[] values = (String[]) field("values");
        for (int index = 0; index < keys.length; index++)
            if (values[index] != null && keys[index] == key) return true;
        return false;
    }

    private static void bounds() throws Exception {
        long[] keys = (long[]) field("keys");
        String[] values = (String[]) field("values");
        require(keys.length == 128 && values.length == 128, "Fixed primitive/string capacity");
        require((Integer) field("next") >= 0 && (Integer) field("next") < 128, "Bounded cursor");
        HashSet<Long> seen = new HashSet<>();
        for (int index = 0; index < keys.length; index++) if (values[index] != null) {
            double amount = Double.longBitsToDouble(keys[index]);
            require(Double.isFinite(amount), "Non-finite key was retained");
            require(seen.add(keys[index]), "Duplicate raw key retained");
            require(values[index].length() <= 384, "Long result retained");
            require(values[index].equals(OriginalTableAmounts.formatAmount(amount)), "Retained key/value mismatch");
        }
    }

    private static void edgesAndRandom() throws Exception {
        double[] edges = {0d, -0d, Double.MIN_VALUE, -Double.MIN_VALUE,
            Double.MIN_NORMAL, -Double.MIN_NORMAL, Double.MAX_VALUE, -Double.MAX_VALUE,
            0.004d, 0.005d, 0.006d, -0.004d, -0.005d, -0.006d,
            1.004d, 1.005d, 1.006d, -1.004d, -1.005d, -1.006d,
            2.675d, -2.675d, 1e-308d, -1e-308d, 1e308d, -1e308d,
            16777216d, 16777217d, 9007199254740991d, 9007199254740992d};
        for (double edge : edges) {
            compare(edge); compare(Math.nextDown(edge)); compare(Math.nextUp(edge));
        }
        Random random = new Random(18364079L);
        for (int index = 0; index < 16000; index++) {
            double value = Double.longBitsToDouble(random.nextLong());
            if (Double.isFinite(value)) compare(value);
        }
        for (int index = -2000; index <= 2000; index++) {
            double midpoint = index / 100d + .005d;
            compare(midpoint); compare(Math.nextDown(midpoint)); compare(Math.nextUp(midpoint));
        }
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            for (Locale locale : new Locale[]{Locale.US, Locale.GERMANY, new Locale("ar"), new Locale("tr")}) {
                Locale.setDefault(Locale.Category.FORMAT, locale);
                for (double edge : edges) compare(edge);
            }
        } finally { Locale.setDefault(Locale.Category.FORMAT, previous); }
        compare(16777216d); compare(16777217d);
        require(!CoronaPokerGdxTable.formatAmount(16777216d).equals(CoronaPokerGdxTable.formatAmount(16777217d)),
                "Full double key must not collapse float-equal values");
        compare(0d); compare(-0d);
        require(retained(0d) && retained(-0d), "Signed zero keys remain distinct");
    }

    private static RuntimeException failure(double amount, boolean cached) {
        try {
            if (cached) CoronaPokerGdxTable.formatAmount(amount);
            else OriginalTableAmounts.formatAmount(amount);
        } catch (RuntimeException failure) { return failure; }
        throw new AssertionError("Non-finite amount did not fail");
    }

    private static void nonFinite() throws Exception {
        double[] values = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.longBitsToDouble(0x7ff0000000000001L), Double.longBitsToDouble(0xfff8000000000002L)};
        int cursor = (Integer) field("next");
        for (double value : values) {
            RuntimeException expected = failure(value, false), actual = failure(value, true);
            require(expected.getClass() == actual.getClass(), "Exception type changed");
            require(expected.getMessage().equals(actual.getMessage()), "Exception message changed");
            require(actual != failure(value, true), "Exception object was cached");
            require(!retained(value), "Non-finite key was cached");
        }
        require(cursor == (Integer) field("next"), "Non-finite attempts changed eviction state");
    }

    private static void eviction() throws Exception {
        double first = 100000.125d;
        String originalResult = CoronaPokerGdxTable.formatAmount(first);
        for (int index = 1; index < 128; index++) compare(first + index * 3.75d);
        require(retained(first), "128 distinct values fit");
        require(originalResult == CoronaPokerGdxTable.formatAmount(first), "Steady result identity reused");
        compare(first + 128 * 3.75d);
        require(!retained(first), "129th value evicts the oldest slot");
        String recomputed = CoronaPokerGdxTable.formatAmount(first);
        require(recomputed.equals(originalResult) && recomputed != originalResult, "Evicted value recomputes from oracle");
        for (int index = 0; index < 1024; index++) compare(first + index * 7.125d);
        bounds();
    }

    private static void concurrentTables() throws Exception {
        int workers = 8, iterations = 4000;
        CountDownLatch ready = new CountDownLatch(workers), start = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread[] threads = new Thread[workers];
        for (int worker = 0; worker < workers; worker++) {
            final int id = worker;
            threads[worker] = new Thread(() -> {
                try {
                    ready.countDown(); start.await();
                    for (int index = 0; index < iterations; index++) {
                        double value = ((index * 17 + id) % 193 - 96) * 2.675d;
                        if ((index & 63) == 0) value = (id & 1) == 0 ? 0d : -0d;
                        String expected = OriginalTableAmounts.formatAmount(value);
                        if (!expected.equals(CoronaPokerGdxTable.formatAmount(value)))
                            throw new AssertionError("Concurrent raw key/result mismatch");
                        if ((index & 127) == 0 && failure(Double.NaN, true).getClass() != NumberFormatException.class)
                            throw new AssertionError("Concurrent exception differs");
                    }
                } catch (Throwable error) { failed.compareAndSet(null, error); }
            }, "amount-table-" + worker);
            threads[worker].start();
        }
        ready.await(); start.countDown();
        for (Thread thread : threads) thread.join();
        require(failed.get() == null, "Concurrent comparisons failed: " + failed.get());
        checks += workers * iterations;
        bounds();
    }

    private static long measuredHits(ThreadMXBean bean, long thread, double[] values) {
        long before = bean.getThreadAllocatedBytes(thread);
        for (int index = 0; index < 100000; index++)
            blackhole = CoronaPokerGdxTable.formatAmount(values[index & 63]);
        return bean.getThreadAllocatedBytes(thread) - before;
    }

    private static long steadyAllocations() {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        require(bean.isThreadAllocatedMemorySupported(), "Host allocation counter available");
        bean.setThreadAllocatedMemoryEnabled(true);
        double[] values = new double[64];
        for (int index = 0; index < values.length; index++) values[index] = 1234.567d + index * 1.125d;
        for (int round = 0; round < 3; round++) for (int index = 0; index < 100000; index++)
            blackhole = CoronaPokerGdxTable.formatAmount(values[index & 63]);
        long thread = Thread.currentThread().getId(), allocated = 0;
        for (int round = 0; round < 5; round++) {
            long bytes = measuredHits(bean, thread, values);
            if (round >= 3) {
                require(bytes == 0, "Steady production helper allocated " + bytes + " bytes");
                allocated += bytes;
            }
        }
        return allocated;
    }

    private static long originalAllocationBaseline() {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        for (int index = 0; index < 10000; index++) blackhole = OriginalTableAmounts.formatAmount(1234.567d);
        long before = bean.getThreadAllocatedBytes(thread);
        for (int index = 0; index < 10000; index++) blackhole = OriginalTableAmounts.formatAmount(1234.567d);
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        require(bytes > 0, "Original formatter allocation baseline was not observed");
        return bytes;
    }

    public static void main(String[] args) throws Exception {
        edgesAndRandom(); nonFinite(); eviction(); concurrentTables();
        long bytes = steadyAllocations(), baseline = originalAllocationBaseline();
        require(IOSTableAmountCache.formatAmount(1234.567d).equals(OriginalTableAmounts.formatAmount(1234.567d)),
                "Direct production helper agrees");
        System.out.println("{\"passed\":true,\"checks\":" + checks + ",\"concurrentComparisons\":32000,"
                + "\"steadyMeasuredCalls\":200000,\"steadyAllocatedBytes\":" + bytes
                + ",\"uncachedBaselineCalls\":10000,\"uncachedBaselineAllocatedBytes\":" + baseline
                + ",\"capacity\":128,\"maximumRetainedResultLength\":384}");
    }
}
