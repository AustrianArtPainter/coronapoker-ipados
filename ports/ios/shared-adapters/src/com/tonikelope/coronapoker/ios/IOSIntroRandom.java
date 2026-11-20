package com.tonikelope.coronapoker.ios;

import java.util.SplittableRandom;

/**
 * Scalar Java 17 RandomGenerator semantics over the runtime's original
 * SplittableRandom seed/mix operations. Compile with --release 8 so startup
 * never acquires a downgraded RandomGenerator bridge dependency.
 * This generator is only for the upstream decorative startup composition.
 */
public strictfp final class IOSIntroRandom {
    private final SplittableRandom random;

    public IOSIntroRandom(long seed) {
        random = new SplittableRandom(seed);
    }

    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        int mask = bound - 1;
        int value = random.nextInt();
        if ((bound & mask) == 0) return value & mask;
        for (int positive = value >>> 1;
                positive + mask - (value = positive % bound) < 0;
                positive = random.nextInt() >>> 1) {
            // Reject the incomplete upper range, exactly as Java 17 does.
        }
        return value;
    }

    public double nextDouble() {
        return (random.nextLong() >>> 11) * 0x1.0p-53;
    }

    public double nextDouble(double origin, double bound) {
        if (!(origin < bound && bound - origin < Double.POSITIVE_INFINITY)) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        double value = nextDouble() * (bound - origin) + origin;
        // Java 8's legacy bit decrement differs for negative upper bounds.
        // Preserve Java 17's directional rounding correction instead.
        return value >= bound ? Math.nextAfter(bound, origin) : value;
    }

    public boolean nextBoolean() {
        return random.nextInt() < 0;
    }
}
