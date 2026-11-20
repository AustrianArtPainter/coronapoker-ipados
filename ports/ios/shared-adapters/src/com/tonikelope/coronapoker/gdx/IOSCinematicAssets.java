package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.Gdx;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Table-owned ALL IN frames. Preparation finishes before the table opens. */
final class IOSCinematicAssets {
    static final String[] OFFICIAL = {"casinoroyale.gif", "hulk.gif", "joker.gif",
            "nicolas_cage.gif", "nicolas_cage2.gif", "rounders.gif", "terminator2.gif",
            "training_day.gif", "wallstreet.gif"};
    private static final long BUDGET = 384L * 1024 * 1024;
    private final Map<String, PreloadedShuffleAnimation> owned = new LinkedHashMap<>();
    private CompletableFuture<PreloadedShuffleAnimation> preparation;
    private Iterator<Map.Entry<String, Path>> remaining;
    private PreloadedShuffleAnimation uploading;
    private String preparingName;
    private long reserved;
    private boolean ready, disposed;

    void prepare(GdxGamePresentationSettings settings) {
        if (preparation != null || remaining != null || uploading != null || ready) return;
        LinkedHashMap<String, Path> sources = new LinkedHashMap<>();
        for (String name : OFFICIAL) sources.put(name, settings == null ? null
                : settings.modAsset("cinematics/allin/" + name).orElse(null));
        if (settings != null) for (Path path : settings.modFiles("cinematics/allin", ".gif"))
            sources.put(path.getFileName().toString(), path);
        remaining = sources.entrySet().iterator();
        prepareNext();
    }

    private void prepareNext() {
        if (!remaining.hasNext()) {
            remaining=null; ready=true; IOSFrameEvidence.allInPreparedClips=owned.size(); return;
        }
        Map.Entry<String, Path> entry=remaining.next();
        preparingName=entry.getKey();
        preparation=GifFramePreparation.start(() -> entry.getValue()==null
                ? Gdx.files.internal("cinematics/allin/"+entry.getKey()).readBytes()
                : Files.readAllBytes(entry.getValue()), 563, Math.min(256L*1024*1024, BUDGET-reserved));
    }

    /** GL thread; uploads one prepared image per creation step, never during play. */
    boolean uploadNext() {
        if (disposed) throw new IllegalStateException("Cinematic assets disposed");
        if (ready) return true;
        if (remaining == null && preparation == null) return true; // Startup intro has no live table.
        if (uploading == null) {
            if (!preparation.isDone()) return false;
            try { uploading=preparation.join(); }
            catch (java.util.concurrent.CompletionException failure) {
                if (!(failure.getCause() instanceof GifFramePreparation.BudgetExceeded)) throw failure;
                preparation=null; prepareNext(); return false; // Preserve external-mod fallback.
            }
            preparation=null; owned.put(preparingName, uploading); reserved+=uploading.gpuBytes();
        }
        if (uploading.uploadNext()) { uploading=null; prepareNext(); }
        return false;
    }

    PreloadedShuffleAnimation prepared(String name) {
        if (!ready || disposed) throw new IllegalStateException("ALL IN assets must be ready before START");
        return owned.get(name);
    }
    Set<String> names() { return owned.keySet(); }
    void dispose() {
        if (disposed) return;
        disposed = true;
        GifFramePreparation.cancel(preparation);
        preparation = null; remaining = null; uploading = null;
        for (PreloadedShuffleAnimation animation : owned.values()) animation.dispose();
        owned.clear();
    }
}
