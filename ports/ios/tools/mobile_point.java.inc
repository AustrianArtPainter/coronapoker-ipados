    private static final ThreadLocal<MobilePointScratch> MOBILE_POINT =
            ThreadLocal.withInitial(MobilePointScratch::new);

    /** Mutable only within one thread/call; published EdwardsPoint values stay immutable. */
    private static final class MobilePointScratch {
        final int[] x = X25519Field.create(), y = X25519Field.create();
        final int[] z = X25519Field.create(), t = X25519Field.create();
        final int[] a = X25519Field.create(), b = X25519Field.create();
        final int[] c = X25519Field.create(), d = X25519Field.create();
        final int[] e = X25519Field.create(), f = X25519Field.create();
        final int[] g = X25519Field.create(), h = X25519Field.create();
        int[] naf = new int[260];
        BigInteger[] scalars = new BigInteger[64];
        EdwardsPoint[][] tables = new EdwardsPoint[64][];

        void identity() {
            X25519Field.zero(x); X25519Field.one(y);
            X25519Field.one(z); X25519Field.zero(t);
        }
        void load(EdwardsPoint point) {
            System.arraycopy(point.X.readOnlyLimbs(), 0, x, 0, 10);
            System.arraycopy(point.Y.readOnlyLimbs(), 0, y, 0, 10);
            System.arraycopy(point.Z.readOnlyLimbs(), 0, z, 0, 10);
            System.arraycopy(point.T.readOnlyLimbs(), 0, t, 0, 10);
        }
        static void plus(int[] x, int[] y, int[] out) {
            X25519Field.add(x, y, out); X25519Field.carry(out); X25519Field.normalize(out);
        }
        static void minus(int[] x, int[] y, int[] out) {
            X25519Field.sub(x, y, out); X25519Field.carry(out); X25519Field.normalize(out);
        }
        static void times(int[] x, int[] y, int[] out) {
            X25519Field.mul(x, y, out); X25519Field.normalize(out);
        }
        static void square(int[] x, int[] out) {
            X25519Field.sqr(x, out); X25519Field.normalize(out);
        }
        void add(EdwardsPoint q) { add(q, false); }
        void add(EdwardsPoint q, boolean subtract) {
            int[] qx = q.X.readOnlyLimbs(), qy = q.Y.readOnlyLimbs();
            minus(y, x, a);
            if (subtract) plus(qy, qx, e); else minus(qy, qx, e);
            times(a, e, a);
            plus(y, x, b);
            if (subtract) minus(qy, qx, e); else plus(qy, qx, e);
            times(b, e, b);
            times(t, D2.readOnlyLimbs(), c);
            times(c, q.T.readOnlyLimbs(), c);
            if (subtract) { X25519Field.negate(c, c); X25519Field.carry(c); X25519Field.normalize(c); }
            times(z, q.Z.readOnlyLimbs(), d); plus(d, d, d);
            minus(b, a, e); minus(d, c, f); plus(d, c, g); plus(b, a, h);
            times(e, f, x); times(g, h, y); times(f, g, z); times(e, h, t);
        }
        void twice() {
            square(x, a); square(y, b); square(z, c); plus(c, c, c);
            X25519Field.negate(a, d); X25519Field.carry(d); X25519Field.normalize(d);
            plus(x, y, e); square(e, e); minus(e, a, e); minus(e, b, e);
            plus(d, b, g); minus(g, c, f); minus(d, b, h);
            times(e, f, x); times(g, h, y); times(f, g, z); times(e, h, t);
        }
        EdwardsPoint freeze() {
            return new EdwardsPoint(Fe25519.fromOwnedLimbs(x.clone()), Fe25519.fromOwnedLimbs(y.clone()),
                    Fe25519.fromOwnedLimbs(z.clone()), Fe25519.fromOwnedLimbs(t.clone()));
        }
    }
