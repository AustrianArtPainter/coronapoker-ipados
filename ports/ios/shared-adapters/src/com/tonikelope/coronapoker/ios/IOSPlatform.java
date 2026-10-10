package com.tonikelope.coronapoker.ios;

import java.io.IOException;
import java.nio.file.Path;

/** UIKit/Keychain/microphone boundaries, statically linked into the app. */
public final class IOSPlatform {
    /** Read actual QoS on a Foundation compute worker; returns class or negative errno. */
    public static native int currentThreadQoS();
    public static Path temporaryDirectory() {
        return Path.of(org.robovm.apple.foundation.NSFileManager.getDefaultManager()
                .getTemporaryDirectory().getPath());
    }
    public static native void setFrameRate(int framesPerSecond);
    /** Docked keyboard overlap as a fraction of the native game view height. */
    public static native float keyboardOcclusion();
    /** Native safe-area fractions: left, top, right, bottom. */
    public static native float[] safeAreaInsets();
    public static native void protectDirectory(String path) throws IOException;
    public static native byte[] readIdentity(String account) throws IOException;
    /** Returns false only when another caller atomically created this identity. */
    public static native boolean createIdentity(String account, byte[] data) throws IOException;
    private static native String selectImage() throws IOException;
    public static Path pickImage() {
        try { String path = selectImage(); return path == null ? null : Path.of(path); }
        catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    public static native boolean copyImage(String path);
    public static native void idleTimerDisabled(boolean disabled);
    public static native boolean startMicrophone(String input) throws IOException;
    public static native byte[] stopMicrophone() throws IOException;
    public static native void abortMicrophone();
    public static native double microphoneSeconds();
    public static native String[] microphoneInputs();
    public static native String[] audioOutputs();
    public static native boolean useMicrophone(String uid);
    private IOSPlatform() {}
}
