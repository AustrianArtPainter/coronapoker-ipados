/**
 * iOS field backend for the unchanged upstream Ristretto/proof implementation.
 * Owns one normalized ten-limb array per immutable value. Bouncy Castle is
 * already a pinned game dependency; no wire format or proof gate changes.
 */
public final class Fe25519 {
    public static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    public static final BigInteger SQRT_M1 = new BigInteger(
            "19681161376707505956807079304988542015446066515923890162744021073123829784752");
    public static final Fe25519 ZERO = of(0);
    public static final Fe25519 ONE = of(1);
    private static final Fe25519 SQRT_M1_FE = of(SQRT_M1);
    private static final byte[] PRIME_LE = primeBytes();
    private final int[] value;

    // Package-private read access for the curve accumulator. Its operands
    // are immutable; every field operation writes into separate scratch.
    int[] readOnlyLimbs() { return value; }
    static Fe25519 fromOwnedLimbs(int[] owned) { return new Fe25519(owned); }

    // Every array passed here is newly owned and never exposed or changed
    // after publication. Scratch arrays in pow22523 remain local to the call.
    private Fe25519(int[] owned) {
        X25519Field.normalize(owned);
        value = owned;
    }
    public static Fe25519 of(BigInteger input) {
        byte[] be = input.mod(P).toByteArray();
        byte[] le = new byte[32];
        for (int i = 0; i < be.length && i < 32; i++) le[i] = be[be.length - 1 - i];
        return fromBytes(le);
    }
    public static Fe25519 of(long input) { return of(BigInteger.valueOf(input)); }
    private static byte[] primeBytes() {
        byte[] result = new byte[32];
        java.util.Arrays.fill(result, (byte) 0xff);
        result[0] = (byte) 0xed;
        result[31] = 0x7f;
        return result;
    }
    public static Fe25519 fromBytes(byte[] input) {
        if (input == null || input.length != 32)
            throw new IllegalArgumentException("field element must be 32 bytes");
        int[] result = X25519Field.create();
        X25519Field.decode255(input, 0, result, 0);
        return new Fe25519(result);
    }
    public static Fe25519 fromBytesCanonical(byte[] input) {
        if (input == null || input.length != 32) return null;
        // Compare the full unsigned 256-bit input BEFORE decode255 masks it.
        for (int i = 31; i >= 0; i--) {
            int a = input[i] & 255, b = PRIME_LE[i] & 255;
            if (a > b) return null;
            if (a < b) return fromBytes(input);
        }
        return null; // p itself is not canonical
    }
    public byte[] toBytes() {
        byte[] result = new byte[32];
        X25519Field.encode(value, result, 0);
        return result;
    }
    public Fe25519 add(Fe25519 other) {
        int[] result = X25519Field.create();
        X25519Field.add(value, other.value, result);
        X25519Field.carry(result);
        return new Fe25519(result);
    }
    public Fe25519 sub(Fe25519 other) {
        int[] result = X25519Field.create();
        X25519Field.sub(value, other.value, result);
        X25519Field.carry(result);
        return new Fe25519(result);
    }
    public Fe25519 mul(Fe25519 other) {
        int[] result = X25519Field.create();
        X25519Field.mul(value, other.value, result);
        return new Fe25519(result);
    }
    public Fe25519 sqr() {
        int[] result = X25519Field.create();
        X25519Field.sqr(value, result);
        return new Fe25519(result);
    }
    public Fe25519 negate() {
        if (isZero()) return ZERO;
        int[] result = X25519Field.create();
        X25519Field.negate(value, result);
        X25519Field.carry(result);
        return new Fe25519(result);
    }
    public Fe25519 pow(BigInteger exponent) {
        // Retain BigInteger semantics, including negative exponents, at this
        // infrequent API boundary. The hot fixed exponent uses limbs below.
        return of(toBigInteger().modPow(exponent, P));
    }
    Fe25519 pow22523() {
        int[] t0 = X25519Field.create();
        int[] t1 = X25519Field.create();
        int[] t2 = X25519Field.create();
        // The original ref10 addition chain, with the same exponents and
        // in-place scratch buffers instead of ~260 immutable intermediates.
        X25519Field.sqr(value, t0);           // 2
        X25519Field.sqr(t0, 2, t1);          // 8
        X25519Field.mul(value, t1, t1);      // 9
        X25519Field.mul(t0, t1, t0);         // 11
        X25519Field.sqr(t0, t0);             // 22
        X25519Field.mul(t1, t0, t0);         // 2^5 - 1
        X25519Field.sqr(t0, 5, t1);
        X25519Field.mul(t1, t0, t0);         // 2^10 - 1
        X25519Field.sqr(t0, 10, t1);
        X25519Field.mul(t1, t0, t1);         // 2^20 - 1
        X25519Field.sqr(t1, 20, t2);
        X25519Field.mul(t2, t1, t1);         // 2^40 - 1
        X25519Field.sqr(t1, 10, t1);
        X25519Field.mul(t1, t0, t0);         // 2^50 - 1
        X25519Field.sqr(t0, 50, t1);
        X25519Field.mul(t1, t0, t1);         // 2^100 - 1
        X25519Field.sqr(t1, 100, t2);
        X25519Field.mul(t2, t1, t1);         // 2^200 - 1
        X25519Field.sqr(t1, 50, t1);
        X25519Field.mul(t1, t0, t0);         // 2^250 - 1
        X25519Field.sqr(t0, 2, t0);          // 2^252 - 4
        X25519Field.mul(t0, value, t0);      // 2^252 - 3
        return new Fe25519(t0);
    }
    public Fe25519 inv() {
        if (isZero()) return ZERO; // original Fermat definition: inv(0) == 0
        int[] result = X25519Field.create();
        X25519Field.inv(value, result);
        return new Fe25519(result);
    }
    public boolean isZero() { return X25519Field.isZeroVar(value); }
    public boolean isNegative() { return (value[0] & 1) != 0; }
    public Fe25519 abs() { return isNegative() ? negate() : this; }
    public boolean ctEq(Fe25519 other) { return X25519Field.areEqualVar(value, other.value); }
    public BigInteger toBigInteger() {
        byte[] bytes = toBytes();
        for (int i = 0; i < 16; i++) {
            byte saved = bytes[i]; bytes[i] = bytes[31 - i]; bytes[31 - i] = saved;
        }
        return new BigInteger(1, bytes);
    }
    @Override public boolean equals(Object other) {
        return other instanceof Fe25519 && ctEq((Fe25519) other);
    }
    @Override public int hashCode() { return toBigInteger().hashCode(); }
    @Override public String toString() { return "Fe25519(" + toBigInteger().toString(16) + ")"; }

    // Keep the original RFC 9496 sqrt_ratio_i logic, including zero cases.
    public static SqrtRatioResult sqrtRatioM1(Fe25519 u, Fe25519 v) {
        Fe25519 v3 = v.sqr().mul(v);
        Fe25519 v7 = v3.sqr().mul(v);
        Fe25519 r = u.mul(v3).mul(u.mul(v7).pow22523());
        Fe25519 check = v.mul(r.sqr());
        Fe25519 uNeg = u.negate();
        Fe25519 uNegI = uNeg.mul(SQRT_M1_FE);
        boolean correctSignSqrt = check.ctEq(u);
        boolean flippedSignSqrt = check.ctEq(uNeg);
        boolean flippedSignSqrtI = check.ctEq(uNegI);
        Fe25519 rPrime = r.mul(SQRT_M1_FE);
        if (flippedSignSqrt || flippedSignSqrtI) r = rPrime;
        return new SqrtRatioResult(correctSignSqrt || flippedSignSqrt, r.abs());
    }
    public static final class SqrtRatioResult {
        public final boolean wasSquare;
        public final Fe25519 r;
        SqrtRatioResult(boolean wasSquare, Fe25519 r) { this.wasSquare = wasSquare; this.r = r; }
    }
