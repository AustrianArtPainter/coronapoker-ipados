package com.tonikelope.coronapoker.gdx;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Presents Files on UIKit's main queue while the game keeps rendering. */
final class GdxFileChooser {
    static CompletableFuture<Path> chooseDirectory(Path initial,String title,String approve) {
        return choose(initial,title,null);
    }
    static CompletableFuture<Path> chooseFile(Path initial,String title,String approve,String description,String... extensions) {
        return choose(initial,title,String.join(",",extensions));
    }
    private static CompletableFuture<Path> choose(Path initial,String title,String extensions) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String path = pick(initial.toString(),title,extensions);
                return path == null ? null : Path.of(path);
            } catch (IOException failed) { throw new UncheckedIOException(failed); }
        });
    }
    private static native String pick(String initial,String title,String extensions) throws IOException;
}
