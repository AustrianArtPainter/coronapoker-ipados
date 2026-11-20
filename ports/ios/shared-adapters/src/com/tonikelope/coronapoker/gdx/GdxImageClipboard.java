package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.ios.IOSPlatform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Publish the actual screenshot image to the native iOS pasteboard. */
final class GdxImageClipboard {
    static void prepare(Path file) { /* UIImage decoding is bounded by the screenshot dimensions. */ }
    static boolean copy(Path file) throws IOException {
        if (file == null) return false;
        if (!Files.isRegularFile(file)) throw new IOException("Screenshot file is unavailable");
        return IOSPlatform.copyImage(file.toAbsolutePath().toString());
    }
    static void discard(Path file) {}
    private GdxImageClipboard() {}
}
