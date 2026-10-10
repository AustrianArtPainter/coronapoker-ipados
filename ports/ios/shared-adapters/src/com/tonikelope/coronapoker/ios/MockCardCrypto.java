package com.tonikelope.coronapoker.ios;

import java.util.concurrent.atomic.AtomicLong;

/** Temporary, explicit offline-only card-crypto isolation. Never a network protocol. */
public final class MockCardCrypto {
    public static final boolean ENABLED = "mock".equals(System.getenv("CP_IOS_CRYPTO_TEST"));
    public static final AtomicLong DEALS = new AtomicLong(), REVEALS = new AtomicLong();
    public static final AtomicLong REAL_CALLS = new AtomicLong(), SIGNATURES = new AtomicLong();
    public static final AtomicLong NORMAL_CALLS = new AtomicLong();
    static {
        if (ENABLED && (!"offline".equals(System.getenv("CP_IOS_SCENARIO"))
                || System.getenv("CP_IOS_RUN_ID") == null
                || !System.getenv("CP_IOS_RUN_ID").startsWith("crypto-mock-")
                || SilentAudio.ENABLED))
            throw new IllegalStateException("Crypto mock requires an isolated offline diagnostic with real audio");
    }
    public static void requireReal(String method) {
        if (!ENABLED && IOSPerformance.ENABLED) NORMAL_CALLS.incrementAndGet();
        if (ENABLED) {
            REAL_CALLS.incrementAndGet();
            throw new IllegalStateException("Real card crypto reached during isolation: " + method);
        }
    }
    public static byte[] scalar() { byte[] result = new byte[32]; result[0] = 1; return result; }
    public static byte[] deck(int hand) {
        byte[] result = new byte[52 * 32];
        for (int i=0; i<52; i++) result[i*32] = (byte) ((i * 17 + hand * 7) % 52);
        return result;
    }
    public static int card(byte[] point) { return point != null && point.length == 32 ? point[0] & 255 : -1; }
    public static boolean valid(byte[] points) {
        if (points == null || points.length == 0 || points.length % 32 != 0) return false;
        for (int i=0; i<points.length; i+=32) if ((points[i] & 255) > 51) return false;
        return true;
    }
    public static int[] reveal(byte[] deck, int offset, int count) {
        REVEALS.incrementAndGet();
        int[] result = new int[count];
        for (int i=0; i<count; i++) result[i] = deck[(offset+i)*32] & 255;
        return result;
    }
    public static byte[] signature() {
        SIGNATURES.incrementAndGet();
        byte[] result = new byte[64]; result[0] = 0x4d; return result;
    }
    private MockCardCrypto() {}
}
