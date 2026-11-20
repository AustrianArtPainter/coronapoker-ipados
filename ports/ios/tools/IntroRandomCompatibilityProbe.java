package com.tonikelope.coronapoker.ios;

import java.util.SplittableRandom;

/** Exact seeded differential against the pinned host JDK 17 scalar defaults. */
public final class IntroRandomCompatibilityProbe {
    private static long checks;
    private static void check(boolean condition, String operation, long seed) {
        checks++;
        if (!condition) throw new AssertionError(operation + " differed for seed " + seed);
    }
    private static void same(double expected, double actual, String operation, long seed) {
        check(Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual), operation, seed);
    }
    private static void rejected(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Invalid range was accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void sequence(long seed) {
        SplittableRandom reference = new SplittableRandom(seed);
        IOSIntroRandom mobile = new IOSIntroRandom(seed);
        // The exact Fisher-Yates draw order used for all 52 startup cards.
        int[] originalOrder = new int[52], mobileOrder = new int[52];
        for (int index = 0; index < 52; index++) originalOrder[index] = mobileOrder[index] = index;
        for (int index = 51; index > 0; index--) {
            int originalOther = reference.nextInt(index + 1);
            int mobileOther = mobile.nextInt(index + 1);
            check(originalOther == mobileOther, "startup shuffle", seed);
            int original = originalOrder[index];
            originalOrder[index] = originalOrder[originalOther]; originalOrder[originalOther] = original;
            int replacement = mobileOrder[index];
            mobileOrder[index] = mobileOrder[mobileOther]; mobileOrder[mobileOther] = replacement;
        }
        for (int slot = 0; slot < 52; slot++) {
            check(originalOrder[slot] == mobileOrder[slot], "card identity/draw order", seed);
            range(reference, mobile, slot < 8 ? -0.01f : -0.47f, slot < 8 ? 0.01f : 0.47f, seed);
            range(reference, mobile, slot < 8 ? -0.01f : -0.50f, slot < 8 ? 0.01f : 0.50f, seed);
            range(reference, mobile, -36f, 36f, seed);
            range(reference, mobile, 0.94f, 1.06f, seed);
            same(reference.nextDouble(), mobile.nextDouble(), "card clear delay", seed);
            same(reference.nextDouble(), mobile.nextDouble(), "card exit angle", seed);
            range(reference, mobile, 0.78f, 1.06f, seed);
            range(reference, mobile, -120f, 120f, seed);
            check(reference.nextBoolean() == mobile.nextBoolean(), "card spin sign", seed);
            range(reference, mobile, 430f, 610f, seed);
        }
        int[] bounds = {1, 2, 3, 4, 7, 10, 52, 53, 1023, 1024, 0x40000001, Integer.MAX_VALUE};
        for (int repetition = 0; repetition < 8; repetition++) {
            for (int bound : bounds) check(reference.nextInt(bound) == mobile.nextInt(bound), "bounded/rejection integer", seed);
            // Adjacent negative doubles exercise the rounding correction that
            // makes plain delegation to Java 8's range overload incorrect.
            range(reference, mobile, -2d, Math.nextUp(-2d), seed);
            range(reference, mobile, 1d, Math.nextUp(1d), seed);
            range(reference, mobile, -10d, -1d, seed);
            range(reference, mobile, -1d, 0d, seed);
            check(reference.nextBoolean() == mobile.nextBoolean(), "interleaved sign", seed);
            same(reference.nextDouble(), mobile.nextDouble(), "interleaved double", seed);
        }
        for (int bound : new int[]{0, -1, Integer.MIN_VALUE}) {
            rejected(() -> reference.nextInt(bound)); rejected(() -> mobile.nextInt(bound));
            same(reference.nextDouble(), mobile.nextDouble(), "invalid int consumes no state", seed);
        }
        double[][] invalid = {{0, 0}, {2, 1}, {Double.NaN, 1}, {0, Double.NaN},
                {0, Double.POSITIVE_INFINITY}, {Double.NEGATIVE_INFINITY, 1},
                {-Double.MAX_VALUE, Double.MAX_VALUE}};
        for (double[] boundsPair : invalid) {
            rejected(() -> reference.nextDouble(boundsPair[0], boundsPair[1]));
            rejected(() -> mobile.nextDouble(boundsPair[0], boundsPair[1]));
            same(reference.nextDouble(), mobile.nextDouble(), "invalid double consumes no state", seed);
        }
    }

    private static void range(SplittableRandom reference, IOSIntroRandom mobile,
                              double minimum, double maximum, long seed) {
        same(reference.nextDouble(minimum, maximum), mobile.nextDouble(minimum, maximum), "ranged double", seed);
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            try {
                var expected = java.nio.file.Path.of(args[0]).toRealPath();
                var actual = java.nio.file.Path.of(IOSIntroRandom.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()).toRealPath();
                check(expected.equals(actual), "lowered helper class ownership", 0);
            } catch (Exception failure) {
                throw new AssertionError("Unable to check lowered helper class ownership", failure);
            }
        }
        for (long seed : new long[]{0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 0x123456789abcdefL}) sequence(seed);
        SplittableRandom seeds = new SplittableRandom(0x26_9_17_8L);
        for (int index = 0; index < 4096; index++) sequence(seeds.nextLong());
        System.out.println("PASS " + checks + " exact JDK 17/Java 8 bridge draws across 4102 seeded startup layouts");
    }
}
