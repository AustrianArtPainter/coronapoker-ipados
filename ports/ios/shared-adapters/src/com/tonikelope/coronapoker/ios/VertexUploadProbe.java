package com.tonikelope.coronapoker.ios;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.robovm.rt.GC;

/** One-shot, opt-in black-box measurement of public GDX rendering APIs. */
public final class VertexUploadProbe {
    public static final boolean ENABLED = "1".equals(System.getenv("CP_IOS_VERTEX_PROBE"));
    private static final int COPIED_FLOATS = 20;
    private static final int CALLS = 64;

    public static void run() {
        if (!ENABLED) return;
        if (System.getenv("CP_IOS_SCENARIO") != null)
            throw new IllegalStateException("Vertex API probe requires the idle menu, without a game scenario");
        StringBuilder report = new StringBuilder("{\"copiedFloats\":20,\"callsPerSample\":64,\"samples\":[");
        boolean first = true;
        ByteBuffer bytes = BufferUtils.newUnsafeByteBuffer(COPIED_FLOATS * 4);
        FloatBuffer floats = bytes.asFloatBuffer();
        try {
            for (int capacity : new int[]{20, 1280, 20000, 40000, 100000}) {
                float[] source = new float[capacity];
                for (int i = 0; i < COPIED_FLOATS; i++) source[i] = i;
                for (int api = 0; api < 3; api++) {
                    for (int warm = 0; warm < 4; warm++) copy(api, source, bytes, floats);
                    for (int pass = 0; pass < 3; pass++) {
                        long allocated = GC.getTotalBytes(), started = System.nanoTime();
                        for (int call = 0; call < CALLS; call++) copy(api, source, bytes, floats);
                        long elapsed = System.nanoTime() - started;
                        long delta = GC.getTotalBytes() - allocated;
                        if (!first) report.append(','); first = false;
                        sample(report, new String[]{"noop", "BufferUtils.copy", "FloatBuffer.put"}[api],
                                capacity, pass, delta, elapsed);
                        if (api != 0 && floats.get(COPIED_FLOATS - 1) != COPIED_FLOATS - 1)
                            throw new IllegalStateException("Vertex copy changed data");
                    }
                }
            }
        } finally { BufferUtils.disposeUnsafeByteBuffer(bytes); }
        Pixmap pixel = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pixel.setColor(1, 1, 1, 1); pixel.fill();
        Texture texture;
        try { texture = new Texture(pixel); } finally { pixel.dispose(); }
        try {
            for (int sprites : new int[]{64, 1000, 2000, 5000}) {
                SpriteBatch batch = new SpriteBatch(sprites);
                try {
                    for (int warm = 0; warm < 4; warm++) flush(batch, texture);
                    for (int pass = 0; pass < 3; pass++) {
                        long allocated = GC.getTotalBytes(), started = System.nanoTime();
                        for (int call = 0; call < CALLS; call++) flush(batch, texture);
                        long elapsed = System.nanoTime() - started;
                        long delta = GC.getTotalBytes() - allocated;
                        if (!first) report.append(','); first = false;
                        sample(report, "SpriteBatch.oneSpriteFlush", sprites * 20, pass, delta, elapsed);
                    }
                } finally { batch.dispose(); }
            }
        } finally { texture.dispose(); }
        IOSDiagnostics.report("ios-vertex-upload-probe.json", report.append("]}\n").toString());
    }
    private static void copy(int api, float[] source, ByteBuffer bytes, FloatBuffer floats) {
        if (api == 1) BufferUtils.copy(source, bytes, COPIED_FLOATS, 0);
        else if (api == 2) { floats.position(0); floats.put(source, 0, COPIED_FLOATS); }
    }
    private static void flush(SpriteBatch batch, Texture texture) {
        batch.begin(); batch.draw(texture, 0, 0, 2, 2); batch.end();
    }
    private static void sample(StringBuilder report, String api, int capacity, int pass, long bytes, long nanos) {
        report.append("{\"api\":\"").append(api).append("\",\"sourceCapacityFloats\":").append(capacity)
                .append(",\"pass\":").append(pass).append(",\"allocatedBytes\":").append(bytes)
                .append(",\"elapsedNanos\":").append(nanos).append('}');
    }
    private VertexUploadProbe() {}
}
