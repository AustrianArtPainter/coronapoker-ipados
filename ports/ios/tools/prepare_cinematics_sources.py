"""iOS table owns prepared ALL IN frames; playback borrows without decoding."""


def prepare_streaming(source, replace_method):
    source = source.replace("    private Texture texture;", """    private PreloadedShuffleAnimation mobilePreparedFrames;
    private boolean mobileAllInStream;
    private Texture texture;""")
    source = source.replace("        this.looping = looping;", """        this.looping = looping;
        mobileAllInStream = label.contains("cinematics/allin/");""")
    source = replace_method(source, "    Texture frameAt(", lambda method:
        method.replace("        if (disposed) return null;", """        if (disposed) return null;
        if (mobilePreparedFrames != null) {
            com.badlogic.gdx.graphics.g2d.TextureRegion region = mobilePreparedFrames.frameAt(elapsedSeconds, false);
            return region == null ? null : region.getTexture();
        }"""))
    source = replace_method(source, "    boolean playbackComplete(", lambda method:
        method.replace("        if (failure != null) return true;", """        if (failure != null) return true;
        if (mobilePreparedFrames != null) return Math.max(0L, Math.round(elapsedSeconds * 1000f)) >= durationMs;"""))
    source = replace_method(source, "    private void upload(", lambda method:
        method[:method.index("{")+1] + "\n        if (mobileAllInStream) IOSFrameEvidence.allInHotUploads++;" + method[method.index("{")+1:])
    source = replace_method(source, "    public void dispose()", lambda method:
        method.replace("        if (disposed) return;", """        if (disposed) return;
        if (mobilePreparedFrames != null) {
            disposed = true;
            mobilePreparedFrames = null; // The table owns and disposes the actual textures.
            return;
        }"""))
    extra = '''
    private StreamingGifTextureAnimation(PreloadedShuffleAnimation prepared) {
        if (!prepared.ready()) throw new IllegalStateException("Cinematic not prepared");
        mobilePreparedFrames = prepared;
        decoderThread = null;
        looping = false;
        frameEndMs = null;
        decodedWidth = prepared.width(); decodedHeight = prepared.height();
        durationMs = prepared.durationMillis(); decodingFinished = true;
    }
    static StreamingGifTextureAnimation borrowed(PreloadedShuffleAnimation prepared) {
        return new StreamingGifTextureAnimation(prepared);
    }
'''
    return source[:source.rfind("}")] + extra + "}\n"


def prepare_table(source, replace_method, port):
    source = replace_method(source, "    void beginCreate()", lambda method:
        method.replace("        if (!startupIntroOnly) prepareShuffleAnimation(liveDeck);", """        if (!startupIntroOnly) {
            IOSFrameEvidence.preloadBegin();
            prepareShuffleAnimation(liveDeck);
            mobileAllInAssets.prepare(presentationSettings);
        }"""))
    source = replace_method(source, "    private boolean completeShufflePreparation()", lambda method:
        method.replace("        return mobileShuffleCache == null || mobileShuffleCache.uploadNext();", """        // Drain both bounded producers on every setup frame. Neither may
        // hold a CPU worker while the other waits for its own uploader.
        boolean allInReady = mobileAllInAssets.uploadNext();
        boolean shuffleReady = mobileShuffleCache == null || mobileShuffleCache.uploadNext();
        if (!allInReady || !shuffleReady) return false;
        mobileWarmAllInEffects();
        IOSFrameEvidence.preloadReady();
        return true;"""))
    source = replace_method(source, "    private StreamingGifTextureAnimation allInAnimation(", lambda method:
        method.replace("allInAnimation(", "mobileLegacyAllInAnimation(", 1))
    source = replace_method(source, "    private void drawLiveCinematicOverlay()", lambda method:
        method[:method.index("{")+1] + """
        if (com.tonikelope.coronapoker.ios.AllInIsolation.GIF_OFF && liveAllInCinematicVisible()) return;""" + method[method.index("{")+1:])
    source = replace_method(source, "    private void drawAllInSeatFlames()", lambda method:
        method[:method.index("{")+1] + """
        if (com.tonikelope.coronapoker.ios.AllInIsolation.FIRE_OFF) return;""" + method[method.index("{")+1:])
    for signature in ["    public void dispose()", "    void disposeFailedCreation()"]:
        if signature not in source:
            raise RuntimeError("Cinematic disposal ownership changed: " + signature)
        source = replace_method(source, signature, lambda method:
            method[:method.index("{")+1] + "\n        mobileAllInAssets.dispose();" + method[method.index("{")+1:])
    source = replace_method(source, "    void disposeFailedCreation()", lambda method:
        method.replace("        mobileAllInAssets.dispose();", """        mobileAllInAssets.dispose();
        for (Sound sound : liveCinematicSounds.values()) disposeSafely(sound);
        liveCinematicSounds.clear();"""))
    return source[:source.rfind("}")] + (port / "tools/mobile_cinematics.java.inc").read_text() + "}\n"
