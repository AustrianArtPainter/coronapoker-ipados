package com.tonikelope.coronapoker.ios;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** GIF87a/89a metadata, LZW and disposal without desktop ImageIO or AWT. */
public final class PortableGif {
    private static final int MAX_CANVAS_PIXELS = 16_777_216;
    private static final int MAX_FRAME_COUNT = 16384;
    public record Frame(int left, int top, int width, int height, int delayMillis,
                        int disposal, int transparentIndex, boolean interlaced,
                        int[] palette, int minimumCodeSize, int dataOffset, int dataEnd) {}
    public record Info(int width, int height, List<Frame> frames) {
        public long durationMillis() {
            long result = 0;
            for (Frame frame : frames) result = Math.addExact(result, frame.delayMillis());
            return result;
        }
    }
    public record Pixels(int width, int height, long startMillis, long endMillis, byte[] rgba) {}

    private static final class Input {
        final byte[] data;
        int position;
        Input(byte[] data) { this.data = data; }
        int u8() throws IOException {
            if (position >= data.length) throw new IOException("Truncated GIF");
            return data[position++] & 255;
        }
        int u16() throws IOException { int a = u8(); return a | u8() << 8; }
        void skip(int count) throws IOException {
            if (count < 0 || count > data.length - position) throw new IOException("Truncated GIF block");
            position += count;
        }
        void blocks() throws IOException { int size; while ((size = u8()) != 0) skip(size); }
        int[] palette(int count) throws IOException {
            int[] result = new int[count];
            for (int i = 0; i < count; i++) result[i] = 0xff000000 | u8() << 16 | u8() << 8 | u8();
            return result;
        }
    }

    public static Info inspect(byte[] data) throws IOException {
        return inspect(data, MAX_FRAME_COUNT, MAX_CANVAS_PIXELS);
    }

    public static Info inspect(byte[] data, int maxFrames, long maxCanvasPixels) throws IOException {
        if (data == null || data.length < 13 || data[0] != 'G' || data[1] != 'I' || data[2] != 'F'
                || data[3] != '8' || (data[4] != '7' && data[4] != '9') || data[5] != 'a')
            throw new IOException("Unsupported GIF signature");
        Input input = new Input(data);
        input.position = 6;
        int width = input.u16(), height = input.u16();
        if (width == 0 || height == 0 || (long) width * height > Math.min(MAX_CANVAS_PIXELS, maxCanvasPixels))
            throw new IOException("GIF canvas exceeds decoder limit");
        int flags = input.u8();
        input.skip(2);
        int[] global = (flags & 128) == 0 ? null : input.palette(2 << (flags & 7));
        List<Frame> frames = new ArrayList<>();
        int delay = 100, disposal = 0, transparent = -1;
        boolean trailer = false;
        while (input.position < data.length) {
            int tag = input.u8();
            if (tag == 0x3b) { trailer = true; break; }
            if (tag == 0x21) {
                int extension = input.u8();
                if (extension == 0xf9) {
                    if (input.u8() != 4) throw new IOException("Malformed GIF control block");
                    int control = input.u8();
                    int centiseconds = input.u16();
                    delay = centiseconds <= 1 ? 100 : centiseconds * 10;
                    int color = input.u8();
                    transparent = (control & 1) != 0 ? color : -1;
                    disposal = (control >>> 2) & 7;
                    if (input.u8() != 0) throw new IOException("Malformed GIF control terminator");
                } else input.blocks();
                continue;
            }
            if (tag != 0x2c) throw new IOException("Unknown GIF block " + tag);
            int left = input.u16(), top = input.u16(), fw = input.u16(), fh = input.u16();
            if (fw == 0 || fh == 0 || (long) left + fw > width || (long) top + fh > height)
                throw new IOException("GIF frame lies outside canvas");
            int frameFlags = input.u8();
            int[] palette = (frameFlags & 128) != 0 ? input.palette(2 << (frameFlags & 7)) : global;
            if (palette == null) throw new IOException("GIF has no color table");
            int codeSize = input.u8();
            if (codeSize < 2 || codeSize > 8) throw new IOException("Invalid GIF LZW code size");
            int offset = input.position;
            input.blocks();
            frames.add(new Frame(left, top, fw, fh, delay, disposal, transparent,
                    (frameFlags & 64) != 0, palette, codeSize, offset, input.position));
            if (frames.size() > Math.min(MAX_FRAME_COUNT, maxFrames)) throw new IOException("GIF has too many frames");
            delay = 100; disposal = 0; transparent = -1;
        }
        if (!trailer || frames.isEmpty()) throw new IOException("GIF without complete frame sequence");
        return new Info(width, height, List.copyOf(frames));
    }

    public static void decode(byte[] data, int maxWidth, Consumer<Pixels> consumer,
                              BooleanSupplier cancelled) throws IOException {
        if (maxWidth < 1) throw new IllegalArgumentException("maxWidth <= 0");
        Info info = inspect(data);
        float scale = Math.min(1f, maxWidth / (float) info.width());
        int width = Math.max(1, Math.round(info.width() * scale));
        int height = Math.max(1, Math.round(info.height() * scale));
        int[] canvas = new int[info.width() * info.height()];
        long elapsed = 0;
        for (Frame frame : info.frames()) {
            if (cancelled.getAsBoolean()) return;
            int[] previous = frame.disposal() == 3 ? canvas.clone() : null;
            byte[] indices = lzw(data, frame);
            int sourceRow = 0;
            if (frame.interlaced()) {
                int[] starts = {0, 4, 2, 1}, steps = {8, 8, 4, 2};
                for (int pass = 0; pass < 4; pass++)
                    for (int row = starts[pass]; row < frame.height(); row += steps[pass])
                        drawRow(canvas, info.width(), frame, row, indices, sourceRow++);
            } else {
                for (int row = 0; row < frame.height(); row++)
                    drawRow(canvas, info.width(), frame, row, indices, row);
            }
            byte[] rgba = scaleRgba(canvas, info.width(), info.height(), width, height);
            long end = Math.addExact(elapsed, frame.delayMillis());
            consumer.accept(new Pixels(width, height, elapsed, end, rgba));
            elapsed = end;
            if (frame.disposal() == 2) {
                for (int row = 0; row < frame.height(); row++) {
                    int from = (frame.top() + row) * info.width() + frame.left();
                    Arrays.fill(canvas, from, from + frame.width(), 0);
                }
            } else if (previous != null) canvas = previous;
        }
    }

    private static void drawRow(int[] canvas, int width, Frame frame, int row, byte[] indices,
                                int sourceRow) throws IOException {
        int target = (frame.top() + row) * width + frame.left();
        int source = sourceRow * frame.width();
        for (int col = 0; col < frame.width(); col++) {
            int index = indices[source + col] & 255;
            if (index >= frame.palette().length) throw new IOException("GIF palette index outside color table");
            if (index != frame.transparentIndex()) canvas[target + col] = frame.palette()[index];
        }
    }

    private static byte[] lzw(byte[] data, Frame frame) throws IOException {
        Input input = new Input(data);
        input.position = frame.dataOffset();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        int block;
        while ((block = input.u8()) != 0) {
            if (block > frame.dataEnd() - input.position) throw new IOException("Truncated LZW block");
            compressed.write(data, input.position, block);
            input.skip(block);
        }
        byte[] packed = compressed.toByteArray();
        byte[] output = new byte[Math.multiplyExact(frame.width(), frame.height())];
        short[] prefix = new short[4096];
        byte[] suffix = new byte[4096], stack = new byte[4097];
        int clear = 1 << frame.minimumCodeSize(), end = clear + 1;
        int available = end + 1, size = frame.minimumCodeSize() + 1;
        for (int i = 0; i < clear; i++) suffix[i] = (byte) i;
        int bits = 0, accumulator = 0, cursor = 0, produced = 0, old = -1, first = 0;
        boolean ended = false;
        while (true) {
            while (bits < size) {
                if (cursor >= packed.length) throw new IOException("Truncated GIF LZW stream");
                accumulator |= (packed[cursor++] & 255) << bits;
                bits += 8;
            }
            int code = accumulator & ((1 << size) - 1);
            accumulator >>>= size; bits -= size;
            if (code == clear) { available = end + 1; size = frame.minimumCodeSize() + 1; old = -1; continue; }
            if (code == end) { ended = true; break; }
            if (code > available || code >= 4096) throw new IOException("Invalid GIF LZW code");
            int original = code, depth = 0;
            if (old < 0) {
                if (code >= clear) throw new IOException("Invalid first LZW code");
                if (produced >= output.length) throw new IOException("Excess GIF pixels");
                output[produced++] = suffix[code]; first = code; old = code; continue;
            }
            if (code == available) { stack[depth++] = (byte) first; code = old; }
            while (code >= clear) {
                if (code >= available || depth >= 4096) throw new IOException("Invalid cyclic LZW dictionary");
                stack[depth++] = suffix[code]; code = prefix[code] & 65535;
            }
            first = suffix[code] & 255;
            stack[depth++] = (byte) first;
            while (depth > 0) {
                if (produced >= output.length) throw new IOException("Excess GIF pixels");
                output[produced++] = stack[--depth];
            }
            if (available < 4096) {
                prefix[available] = (short) old; suffix[available++] = (byte) first;
                if (available == (1 << size) && size < 12) size++;
            }
            old = original;
        }
        if (!ended || produced != output.length) throw new IOException("GIF pixel count mismatch");
        return output;
    }

    private static byte[] scaleRgba(int[] pixels, int sw, int sh, int width, int height) {
        byte[] bytes = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
        if (sw == width && sh == height) {
            for (int i = 0; i < pixels.length; i++) {
                int color = pixels[i], offset = i * 4;
                bytes[offset] = (byte) (color >>> 16); bytes[offset + 1] = (byte) (color >>> 8);
                bytes[offset + 2] = (byte) color; bytes[offset + 3] = (byte) (color >>> 24);
            }
            return bytes;
        }
        for (int y = 0; y < height; y++) {
            double sy = Math.max(0, Math.min(sh - 1, (y + 0.5) * sh / height - 0.5));
            int y0 = (int) sy, y1 = Math.min(y0 + 1, sh - 1);
            double fy = sy - y0;
            for (int x = 0; x < width; x++) {
                double sx = Math.max(0, Math.min(sw - 1, (x + 0.5) * sw / width - 0.5));
                int x0 = (int) sx, x1 = Math.min(x0 + 1, sw - 1);
                double fx = sx - x0;
                int c0 = pixels[y0 * sw + x0], c1 = pixels[y0 * sw + x1];
                int c2 = pixels[y1 * sw + x0], c3 = pixels[y1 * sw + x1];
                double a0 = (c0 >>> 24) * (1 - fx) * (1 - fy), a1 = (c1 >>> 24) * fx * (1 - fy);
                double a2 = (c2 >>> 24) * (1 - fx) * fy, a3 = (c3 >>> 24) * fx * fy;
                double alpha = a0 + a1 + a2 + a3;
                double r = ((c0 >>> 16) & 255) * a0 + ((c1 >>> 16) & 255) * a1 + ((c2 >>> 16) & 255) * a2 + ((c3 >>> 16) & 255) * a3;
                double g = ((c0 >>> 8) & 255) * a0 + ((c1 >>> 8) & 255) * a1 + ((c2 >>> 8) & 255) * a2 + ((c3 >>> 8) & 255) * a3;
                double b = (c0 & 255) * a0 + (c1 & 255) * a1 + (c2 & 255) * a2 + (c3 & 255) * a3;
                int offset = (y * width + x) * 4;
                if (alpha > 0) { bytes[offset] = (byte) Math.round(r / alpha); bytes[offset + 1] = (byte) Math.round(g / alpha); bytes[offset + 2] = (byte) Math.round(b / alpha); }
                bytes[offset + 3] = (byte) Math.round(alpha);
            }
        }
        return bytes;
    }
    private PortableGif() {}
}
