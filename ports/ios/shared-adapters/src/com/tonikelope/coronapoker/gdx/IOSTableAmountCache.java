package com.tonikelope.coronapoker.gdx;

/** Bounded pure amount strings shared safely by all table/render callers. */
final class IOSTableAmountCache {
    private static final int CAPACITY = 128;
    // Finite doubles rounded to two places need at most 313 characters
    // (sign, 309 integer digits, decimal point, two fraction digits).
    // Keep an explicit retention bound even if the upstream oracle changes.
    private static final int MAX_RESULT_LENGTH = 384;
    private static final long[] keys = new long[CAPACITY];
    private static final String[] values = new String[CAPACITY];
    private static int next;

    private IOSTableAmountCache() { }

    static synchronized String formatAmount(double amount) {
        // Preserve the original exception, message and fresh exception object.
        // Neither non-finite values nor their failures enter the cache.
        if (!Double.isFinite(amount)) {
            return CoronaPokerGdxTable.mobileUncachedFormatAmount(amount);
        }
        long key = Double.doubleToRawLongBits(amount);
        for (int index = 0; index < values.length; index++) {
            String value = values[index];
            if (value != null && keys[index] == key) return value;
        }
        String result = CoronaPokerGdxTable.mobileUncachedFormatAmount(amount);
        if (result.length() <= MAX_RESULT_LENGTH) {
            keys[next] = key;
            values[next] = result;
            next = (next + 1) % CAPACITY;
        }
        return result;
    }
}
