package com.tonikelope.coronapoker.ios;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.ScreenUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.robovm.apple.foundation.NSFileManager;
import org.robovm.apple.foundation.NSSearchPathDirectory;
import org.robovm.apple.foundation.NSSearchPathDomainMask;

/** Opt-in device proof and startup failures, stored only in this app's container. */
public final class IOSDiagnostics implements ApplicationListener {
    private static volatile Path documents;
    private final ApplicationListener delegate;
    private int frames;
    private int vertexProbeFrames;
    public IOSDiagnostics(ApplicationListener delegate) { this.delegate = delegate; }
    public static void beginRun() {
        try {
            Path path = documents(); IOSPlatform.protectDirectory(path.toString());
            for (String name : new String[]{"ios-startup-failure.txt", "ios-render-status.txt", "ios-first-frame.png",
                    "ios-latest-frame.png", "ios-game-events.jsonl", "ios-game-status.json", "ios-scene-evidence.json"})
                Files.deleteIfExists(path.resolve(name));
        } catch (Exception failure) { throw new IllegalStateException("Unable to prepare app diagnostics", failure); }
    }
    public static Path documents() {
        Path cached = documents;
        if (cached != null) return cached;
        synchronized (IOSDiagnostics.class) {
            if (documents == null) documents = Path.of(NSFileManager.getDefaultManager().getURLsForDirectory(
                    NSSearchPathDirectory.DocumentDirectory, NSSearchPathDomainMask.UserDomainMask).get(0).getPath());
            return documents;
        }
    }
    public static void report(String name, String text) {
        try { Path directory = documents(); Files.createDirectories(directory); Files.write(directory.resolve(name), text.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception failure) { System.err.println("Diagnostic write failed: " + failure); }
    }
    public static void failure(Throwable failure) {
        java.io.StringWriter text = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(text));
        report("ios-startup-failure.txt", text.toString());
    }
    public static void voiceFailure(Throwable failure) {
        java.io.StringWriter text = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(text));
        System.err.println("Voice playback failed: " + text);
        report("ios-voice-playback-failure.txt", text.toString());
    }
    @Override public void create() {
        try { delegate.create(); report("ios-render-status.txt", "GDX create completed"); }
        catch (Throwable failure) { failure(failure); throw failure; }
    }
    @Override public void resize(int width, int height) { delegate.resize(width, height); }
    @Override public void render() {
        long profileStart = IOSPerformance.beginFrame();
        long sceneStart = com.tonikelope.coronapoker.gdx.IOSFrameEvidence.begin();
        long applicationAllocation = IOSPerformance.beginAllocation();
        try {
            delegate.render();
            if (VertexUploadProbe.ENABLED && ++vertexProbeFrames == 120) VertexUploadProbe.run();
            if ("1".equals(System.getenv("CP_IOS_DIAGNOSTICS")) && (++frames == 300 || frames % 1800 == 0)) {
                Pixmap image = ScreenUtils.getFrameBufferPixmap(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                String name = frames == 300 ? "ios-first-frame.png" : "ios-latest-frame.png";
                try { PixmapIO.writePNG(Gdx.files.absolute(documents().resolve(name).toString()), image, -1, true); }
                finally { image.dispose(); }
                report("ios-render-status.txt", frames + " native GDX frames; screenshot saved: " + name);
            }
        } catch (Throwable failure) { failure(failure); throw failure; }
        finally {
            IOSPerformance.endAllocation("alloc.applicationFrame", applicationAllocation);
            IOSPerformance.endFrame(profileStart);
            com.tonikelope.coronapoker.gdx.IOSFrameEvidence.end(sceneStart);
        }
    }
    @Override public void pause() { com.tonikelope.coronapoker.gdx.IOSFrameEvidence.suspend(); delegate.pause(); }
    @Override public void resume() { com.tonikelope.coronapoker.gdx.IOSFrameEvidence.suspend(); delegate.resume(); }
    @Override public void dispose() { delegate.dispose(); }
}
