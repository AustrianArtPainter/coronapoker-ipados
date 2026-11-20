package com.tonikelope.coronapoker.core;

import com.tonikelope.coronapoker.ios.PortableGif;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Mobile metadata validation with the same desktop byte/pixel/frame limits. */
public final class AvatarImageValidator {
    public static final int MAX_DECODED_BYTES = 256 * 1024;
    public static final int MAX_DIMENSION = 4096;
    public static final long MAX_FRAME_PIXELS = 4_194_304L;
    public static final int MAX_FRAMES = 32;
    public static final long MAX_TOTAL_FRAME_PIXELS = 8_388_608L;

    public static void validate(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path))
            throw new IOException("Avatar file is unavailable");
        long size = Files.size(path);
        if (size <= 0 || size > MAX_DECODED_BYTES) throw new IOException("Avatar exceeds size limit");
        validate(Files.readAllBytes(path));
    }

    public static void validate(byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_DECODED_BYTES)
            throw new IOException("Avatar exceeds size limit");
        if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            PortableGif.Info gif = PortableGif.inspect(data, MAX_FRAMES, MAX_FRAME_PIXELS);
            extent(gif.width(), gif.height());
            if ((long) gif.width() * gif.height() * gif.frames().size() > MAX_TOTAL_FRAME_PIXELS)
                throw new IOException("Avatar total pixels exceed limit");
            for (PortableGif.Frame frame : gif.frames()) extent(frame.width(), frame.height());
            return;
        }
        if (data.length >= 33 && (data[0] & 255) == 137 && data[1] == 'P' && data[2] == 'N'
                && data[3] == 'G' && data[4] == 13 && data[5] == 10 && data[6] == 26 && data[7] == 10
                && be32(data, 8) == 13 && data[12] == 'I' && data[13] == 'H' && data[14] == 'D' && data[15] == 'R') {
            extent(be32(data, 16), be32(data, 20));
            return;
        }
        if (data.length >= 4 && (data[0] & 255) == 255 && (data[1] & 255) == 216) {
            int cursor = 2;
            while (cursor < data.length) {
                if ((data[cursor++] & 255) != 255) throw new IOException("Malformed JPEG marker");
                while (cursor < data.length && (data[cursor] & 255) == 255) cursor++;
                if (cursor >= data.length) break;
                int marker = data[cursor++] & 255;
                if (marker == 217 || marker == 218) break;
                if (marker == 216 || marker == 1 || (marker >= 208 && marker <= 215)) continue;
                if (cursor + 2 > data.length) break;
                int length = (data[cursor] & 255) << 8 | data[cursor + 1] & 255;
                if (length < 2 || length > data.length - cursor) throw new IOException("Truncated JPEG segment");
                if (marker >= 192 && marker <= 207 && marker != 196 && marker != 200 && marker != 204) {
                    if (length < 8) throw new IOException("Truncated JPEG frame");
                    int height = (data[cursor + 3] & 255) << 8 | data[cursor + 4] & 255;
                    int width = (data[cursor + 5] & 255) << 8 | data[cursor + 6] & 255;
                    extent(width, height);
                    return;
                }
                cursor += length;
            }
            throw new IOException("JPEG has no bounded frame dimensions");
        }
        throw new IOException("Unsupported avatar format");
    }

    private static void extent(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_FRAME_PIXELS)
            throw new IOException("Avatar dimensions exceed limit");
    }
    private static int be32(byte[] data, int offset) {
        return (data[offset] & 255) << 24 | (data[offset + 1] & 255) << 16
                | (data[offset + 2] & 255) << 8 | data[offset + 3] & 255;
    }
    private AvatarImageValidator() {}
}
