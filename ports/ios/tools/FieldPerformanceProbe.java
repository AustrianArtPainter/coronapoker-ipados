package com.tonikelope.coronapoker.crypto;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;

/** Desktop allocation comparison, not a substitute for native frame/audio measurements. */
public final class FieldPerformanceProbe {
    private static volatile Object sink;
    public static void main(String[] args) {
        Fe25519 input = Fe25519.of(new BigInteger("91284712894712984791284971298471"));
        BigInteger scalar = new BigInteger("52916238310931284798123712498127984712984712984171293847");
        for (int i = 0; i < 1000; i++) sink = input.pow22523();
        for (int i = 0; i < 100; i++) sink = Ristretto255.encode(EdwardsPoint.BASE.scalarMul(scalar.add(BigInteger.valueOf(i))));
        measure("pow22523", 2000, () -> sink = input.pow22523());
        measure("scalarMul+encode", 300, new Runnable() {
            int i;
            public void run() { sink = Ristretto255.encode(EdwardsPoint.BASE.scalarMul(scalar.add(BigInteger.valueOf(i++)))); }
        });
    }
    private static void measure(String name, int count, Runnable task) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        long allocated = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
        for (int i = 0; i < count; i++) task.run();
        long nanos = System.nanoTime() - start, bytes = bean.getThreadAllocatedBytes(thread) - allocated;
        System.out.printf("%s count=%d bytes/op=%d microseconds/op=%.2f%n", name, count, bytes / count, nanos / (count * 1000.0));
    }
}
