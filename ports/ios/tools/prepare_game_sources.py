#!/usr/bin/env python3
"""Generate reviewed mobile variants, leaving all original desktop sources intact."""
import hashlib
import json
import re
from pathlib import Path

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
GENERATED = PORT / "build/game/generated-src"
CORE = ROOT / "modules/coronapoker-core/src/main/java/com/tonikelope/coronapoker"
GDX = ROOT / "modules/coronapoker-gdx/src/main/java/com/tonikelope/coronapoker/gdx"

def replace_method(source, signature, replacement):
    if source.count(signature) != 1:
        raise RuntimeError("Method signature changed: " + signature)
    start = source.index(signature)
    opening = source.index("{", start)
    cursor, depth, state = opening, 0, "code"
    while cursor < len(source):
        c = source[cursor]
        following = source[cursor:cursor+2]
        if state == "code":
            if following == "//": state = "line"; cursor += 1
            elif following == "/*": state = "comment"; cursor += 1
            elif c == '"': state = "string"
            elif c == "'": state = "char"
            elif c == "{": depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    body = replacement(source[start:cursor+1]) if callable(replacement) else replacement
                    return source[:start] + body + source[cursor+1:]
        elif state == "line":
            if c == "\n": state = "code"
        elif state == "comment":
            if following == "*/": state = "code"; cursor += 1
        elif c == "\\": cursor += 1
        elif (state == "string" and c == '"') or (state == "char" and c == "'"): state = "code"
        cursor += 1
    raise RuntimeError("Unterminated method: " + signature)

def clean_imports(source, prefixes):
    return "\n".join(line for line in source.split("\n")
                      if not any(line.startswith("import " + p) for p in prefixes))

def physical_scissors(source, expected):
    marker = "Gdx.gl.glScissor("
    if source.count(marker) != expected: raise RuntimeError("Clip ownership changed; re-audit pixel units")
    # A normal back buffer and the half-resolution modal capture FBO have
    # different pixel bounds. Use the currently bound GL viewport for both.
    return source.replace(marker, "GdxMobileScissor.set(viewport, ")

def prepare():
    (PORT / "validation").mkdir(parents=True, exist_ok=True)
    results = []
    def emit(original, text):
        source_root = next(parent for parent in original.parents if parent.name == "java")
        target = GENERATED / original.relative_to(source_root)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        results.append({"source": str(original.relative_to(ROOT)),
                        "originalSHA256": hashlib.sha256(original.read_bytes()).hexdigest(),
                        "mobileSHA256": hashlib.sha256(text.encode()).hexdigest()})

    original = GDX / "GdxTableViewState.java"
    text = original.read_text()
    declarations = text[:text.index("    GdxTableViewState(TableSnapshot initialState)")]
    declarations = re.sub(r"/\*.*?\*/|//[^\n]*", "", declarations, flags=re.S)
    fields = [entry for entry in re.findall(
        r"private\s+(?:(static)\s+)?(?:(final)\s+)?([\w.<>?, ]+?)\s+(\w+)\s*(?:=[^;]+)?;", declarations, re.S) if not entry[0]]
    if len(fields) != 52 or not {"snapshot", "nanoTime"}.issubset({field[3] for field in fields}):
        raise RuntimeError("Projection fields changed; re-audit independent mobile state copy")
    copy = "\n    GdxTableViewState mobileCopy() {\n        GdxTableViewState copy = new GdxTableViewState(snapshot, nanoTime);\n"
    for _, final, field_type, name in fields:
        if name == "nanoTime": continue
        if final:
            operation = "putAll" if "Map<" in field_type else "addAll"
            copy += f"        copy.{name}.{operation}({name});\n"
        else: copy += f"        copy.{name} = {name};\n"
    copy += "        return copy;\n    }\n"
    text = text[:text.rfind("}")] + copy + "}\n"
    emit(original, text)

    original = GDX / "GdxTableDialog.java"
    text = original.read_text()
    text = replace_method(text, "    void opened(", '''    void opened(float now) {
        if (Float.isNaN(openedAt)) { openedAt = now; GdxMobileDialogs.opened(this); }
    }''')
    text = replace_method(text, "    boolean expired(", '''    boolean expired(float now) {
        float lifetime = timedLifetimeSeconds();
        return lifetime > 0 && !Float.isNaN(openedAt) && GdxMobileDialogs.elapsed(this) >= lifetime;
    }''')
    text = replace_method(text, "    float remainingFraction(", '''    float remainingFraction(float now) {
        return seconds <= 0 || Float.isNaN(openedAt) ? 0 : Math.max(0, Math.min(1, 1 - GdxMobileDialogs.elapsed(this) / seconds));
    }''')
    text = replace_method(text, "    float elapsedSeconds(", "    float elapsedSeconds(float now) { return GdxMobileDialogs.elapsed(this); }")
    text = replace_method(text, "    int remainingSeconds(", '''    int remainingSeconds(float now) {
        return seconds <= 0 || Float.isNaN(openedAt) ? 0 : Math.max(0, (int) Math.ceil(seconds - GdxMobileDialogs.elapsed(this)));
    }''')
    text = replace_method(text, "    void setTimerPaused(", "    void setTimerPaused(float now, boolean paused) { GdxMobileDialogs.paused(this, paused); }")
    emit(original, text)

    original = GDX / "StreamingGifTextureAnimation.java"
    text = clean_imports(original.read_text(), ["java.awt.", "javax.imageio.", "org.w3c.dom."])
    start = text.index("    static void decode(byte[] data,")
    end = text.index("    private static String sanitizeThreadLabel", start)
    text = text[:start] + '''    static void decode(byte[] data, String label, int maxWidth,
            Consumer<CpuFrame> consumer, BooleanSupplier cancelled) throws IOException {
        com.tonikelope.coronapoker.ios.PortableGif.decode(data, maxWidth,
            frame -> consumer.accept(new CpuFrame(frame.width(), frame.height(),
                frame.startMillis(), frame.endMillis(), frame.rgba())), cancelled);
    }
    static GifTiming inspect(byte[] data, String label, int maxWidth) throws IOException {
        var info = com.tonikelope.coronapoker.ios.PortableGif.inspect(data);
        float scale = Math.min(1f, maxWidth / (float) info.width());
        long[] ends = new long[info.frames().size()];
        long elapsed = 0;
        for (int i = 0; i < ends.length; i++) { elapsed += info.frames().get(i).delayMillis(); ends[i] = elapsed; }
        return new GifTiming(Math.max(1, Math.round(info.width() * scale)),
            Math.max(1, Math.round(info.height() * scale)), ends, elapsed);
    }

''' + text[end:]
    text = text.replace("cycleOffset += durationMs;", "cycleOffset = durationMs;")
    emit(original, text)

    original = CORE / "core/game/ClasspathGameCinematicAssets.java"
    text = clean_imports(original.read_text(), ["javax.imageio.", "org.w3c.dom."])
    start = text.index("    private long measureGif(")
    text = text[:start] + '''    private long measureGif(String resourceName) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            return input == null ? 0L : com.tonikelope.coronapoker.ios.PortableGif.inspect(input.readAllBytes()).durationMillis();
        }
    }
}
'''
    emit(original, text)

    original = CORE / "core/game/ModAwareGameCinematicAssets.java"
    text = clean_imports(original.read_text(), ["javax.imageio.", "org.w3c.dom."])
    start = text.index("    private static long measureGif(")
    text = text[:start] + '''    private static long measureGif(Path path) throws Exception {
        return com.tonikelope.coronapoker.ios.PortableGif.inspect(Files.readAllBytes(path)).durationMillis();
    }
}
'''
    emit(original, text)

    original = CORE / "core/identity/PlayerIdentity.java"
    text = replace_method(original.read_text(), "    public static PlayerIdentity loadOrCreate(", '''    public static PlayerIdentity loadOrCreate(Path coronaDirectory, String nickname) throws IOException {
        Objects.requireNonNull(coronaDirectory, "coronaDirectory");
        String canonical = canonicalNickname(nickname);
        var identity = com.tonikelope.coronapoker.ios.KeychainIdentityStore.loadOrCreate(canonical);
        return new PlayerIdentity(canonical, identity.privateKey(), identity.publicKey());
    }''')
    emit(original, text)

    original = GDX / "GdxVoicePlayback.java"
    text = clean_imports(original.read_text(), ["javax.sound."])
    text = replace_method(text, "    static byte[] playbackPcmWav(", '''    static byte[] playbackPcmWav(byte[] wav) throws IOException {
        return com.tonikelope.coronapoker.ios.PortableWave.decodePcmWave(wav);
    }''')
    temporary = 'temporary = Files.createTempFile("coronapoker-voice-", ".wav");'
    if text.count(temporary) != 1: raise RuntimeError("Voice temporary-file ownership changed")
    text = text.replace(temporary, 'temporary = Files.createTempFile(com.tonikelope.coronapoker.ios.IOSPlatform.temporaryDirectory(), "coronapoker-voice-", ".wav");')
    queue = "                QUEUE);"
    if text.count(queue) != 1: raise RuntimeError("Voice playback completion ownership changed")
    text = text.replace(queue, '''                QUEUE).whenComplete((ignored, failure) -> {
                    if (failure != null) com.tonikelope.coronapoker.ios.IOSDiagnostics.voiceFailure(failure);
                });''')
    text = text.replace("Gdx.audio.newMusic(", "com.tonikelope.coronapoker.ios.IOSMusic.create(")
    text = replace_method(text, "    private static boolean audioOutputAvailable(", '''    private static boolean audioOutputAvailable() {
        return com.tonikelope.coronapoker.ios.IOSPlatform.audioOutputs().length > 0;
    }''')
    emit(original, text)

    original = GDX / "GdxTextToSpeechPlayback.java"
    text = original.read_text().replace("Gdx.audio.newMusic(", "com.tonikelope.coronapoker.ios.IOSMusic.create(")
    emit(original, text)

    original = GDX / "CoronaPokerGdxTable.java"
    text = clean_imports(original.read_text(), ["javax.sound."])
    text = physical_scissors(text, 4)
    marker = "private final GdxTableViewState liveState;"
    if text.count(marker) != 1: raise RuntimeError("Table projection ownership changed")
    text = text.replace(marker, "private GdxTableViewState liveState;")
    text = replace_method(text, "    private static float wavDurationSeconds(", '''    private static float wavDurationSeconds(FileHandle file, float fallback) {
        if (file == null || !file.exists()) return fallback;
        try { return com.tonikelope.coronapoker.ios.PortableWave.durationSeconds(file.readBytes(), fallback); }
        catch (RuntimeException failure) { return fallback; }
    }''')
    text = text.replace("Gdx.audio.newMusic(", "com.tonikelope.coronapoker.ios.IOSMusic.create(")
    # UIKit's invisible input delegate emits typed backspace instead of a
    # physical key-down. Keep the original editing/selection semantics.
    deletion = "            if (character == '\\b') return true;"
    if text.count(deletion) != 1: raise RuntimeError("Table typed deletion changed")
    text = text.replace(deletion, "            if (character == '\\b') return handleChatEditingKey(Input.Keys.BACKSPACE);")
    amount = "                handleAutoCallAmountTyped(character);"
    if text.count(amount) != 1: raise RuntimeError("Auto-call typed input changed")
    text = text.replace(amount, "                if (character == '\\b') handleAutoCallAmountEditingKey(Input.Keys.BACKSPACE);\n                else handleAutoCallAmountTyped(character);")
    # The desktop captures with a physical-key shortcut. On a touch device,
    # use its existing camera actions to capture before opening the catalog.
    camera = "            case SCREENSHOTS -> openScreenshotViewer();"
    final_camera = "        if (action == 4) {\n            openScreenshotViewer();"
    if text.count(camera) != 1 or text.count(final_camera) != 1:
        raise RuntimeError("Screenshot camera ownership changed")
    text = text.replace(camera, "            case SCREENSHOTS -> mobileCaptureAndViewScreenshot();")
    text = text.replace(final_camera, "        if (action == 4) {\n            mobileCaptureAndViewScreenshot();")
    def mobile_capture(writer):
        # Retain the original render-thread readback and background PNG writer.
        writer = writer.replace("        int width =", "        long mobileRequest = mobileScreenshotRequest;\n        int width =", 1)
        png = "                    PixmapIO.writePNG(new FileHandle(file.toFile()), pixmap);"
        failure = "            } catch (RuntimeException | IOException failure) {\n                result = failedText;"
        completion = "                Gdx.app.postRunnable(() -> {\n                    screenshotToast = notice;"
        if writer.count(png) != 1 or writer.count(failure) != 1 or writer.count(completion) != 1:
            raise RuntimeError("Screenshot writer completion changed")
        # ScreenUtils already flips the readback; the PNG encoder must not
        # invert it a second time before UIImage receives the file.
        writer = writer.replace(png, "                    PixmapIO.writePNG(new FileHandle(file.toFile()), pixmap, -1, false);")
        writer = writer.replace(failure, failure + "\n                failure.printStackTrace(System.err);")
        return writer.replace(completion, "                Gdx.app.postRunnable(() -> {\n                    if (disposed) return;\n                    mobileScreenshotWritten(mobileRequest, notice.equals(savedText));\n                    screenshotToast = notice;")
    text = replace_method(text, "    private void captureScreenshot() {", mobile_capture)
    for signature in [
        "        public boolean touchDown(int screenX, int screenY, int pointerIndex,\n                int button) {\n",
        "        public boolean touchUp(int screenX, int screenY, int pointerIndex,\n                int button) {\n"]:
        if text.count(signature) != 1: raise RuntimeError("Table touch ownership changed")
        text = text.replace(signature, signature + "            mobileTextHold.cancel();\n")
    dragged = "        public boolean touchDragged(int screenX, int screenY, int pointerIndex) {\n"
    if text.count(dragged) != 1: raise RuntimeError("Table selection drag ownership changed")
    text = text.replace(dragged, dragged + "            mobileTextHold.move(pointerIndex, screenX, screenY);\n")
    chat_hold = "                    chatPointerSelectionDragging = true;\n"
    log_hold = "                gameLogSelectionDragging = true;\n"
    if text.count(chat_hold) != 1 or text.count(log_hold) != 1: raise RuntimeError("Table text capture changed")
    text = text.replace(chat_hold, chat_hold + "                    mobileTextHoldTarget = 1;\n                    mobileTextHold.begin(pointerIndex, screenX, screenY, System.nanoTime());\n")
    text = text.replace(log_hold, log_hold + "                mobileTextHoldTarget = 2;\n                mobileTextHold.begin(pointerIndex, screenX, screenY, System.nanoTime());\n")
    text = replace_method(text, "    private void updateWindowMonitorRefresh(", '''    private void updateWindowMonitorRefresh(float delta) {
        // UIKit owns the display. iOS exposes no desktop monitor selection.
        detectedRefreshRate = 60;
    }''')
    mobile_table = (PORT / "tools/mobile_table_methods.java.inc").read_text()
    text = text[:text.rfind("}")] + mobile_table + "}\n"
    text = text.replace("    public void render() {", "    public void render() {\n        if (mobileResumeRequired) GdxTableRenderer.resumeAll();\n        com.tonikelope.coronapoker.ios.IOSKeyboard.visible((activeDialog != null && activeDialog.isAutoCall()) || (uiLayer == UI_CHAT && !chatImageMode && !blocksTableUtilities(activeDialog)));\n        com.tonikelope.coronapoker.ios.IOSKeyboard.fit(viewport);\n        mobileUpdateTextHold();")
    emit(original, text)

    original = GDX / "GdxApplicationShell.java"
    text = replace_method(original.read_text(), "    private void scheduleJvmSplashClose(", "    private void scheduleJvmSplashClose() { splashCloseScheduled = true; }")
    text = replace_method(text, "    private void updateForegroundFrameRate(", '''    private void updateForegroundFrameRate(float delta) {
        int target = presentationSettings.effectiveFrameRateLimit(refreshRate > 0 ? refreshRate : 60);
        if (target != appliedForegroundFps) { com.tonikelope.coronapoker.ios.IOSPlatform.setFrameRate(target); appliedForegroundFps = target; }
    }''')
    text = text.replace("        Gdx.app.postRunnable(() -> {\n            CoronaPokerGdxTable current = table;\n            if (current == null) {\n                request.dismiss();", "        GdxMobileDialogs.presentInBackground(request);\n        Gdx.app.postRunnable(() -> {\n            if (request.complete() && !request.isNotice()) return;\n            CoronaPokerGdxTable current = table;\n            if (current == null) {\n                request.dismiss();")
    text = text[:text.rfind("}")] + '''
    void mobileValidationLobby(LobbySession opened) {
        application.sessionOpened(); lobby = opened; menu.openLobby(lobby); awaitTable(lobby);
    }
    @Override public void pause() { GdxTableRenderer.suspendAll(); }
    @Override public void resume() {
        Gdx.app.postRunnable(GdxTableRenderer::resumeAll);
    }
''' + "}\n"
    text = text.replace("    public void render() {", "    public void render() {\n        if (startupIntro == null && menu != null) {\n            IOSGameValidation.startOnce(this, sessionGateway, application.service(DatabaseService.class));\n            IOSVoiceValidation.startOnce();\n        }")
    emit(original, text)

    original = GDX / "GdxFrontendScreen.java"
    text = clean_imports(original.read_text(), ["java.awt."])
    text = physical_scissors(text, 3)
    text = replace_method(text, "    private static Path openAvatarFileDialog(", '''    private static Path openAvatarFileDialog(String title) {
        return com.tonikelope.coronapoker.ios.IOSPlatform.pickImage();
    }''')
    text = replace_method(text, "    private void openExternalUri(", '''    private void openExternalUri(URI uri, String failureKey) {
        if (!Gdx.net.openURI(uri.toString())) showToast(gameText.translate(failureKey));
    }''')
    text = replace_method(text, "    private void startUpdateHandoff(", '''    private void startUpdateHandoff() {
        openLatestRelease();
        showToast("iOS App updates require installation through Xcode on your Mac.");
    }''')
    text = text.replace("Gdx.audio.newMusic(", "com.tonikelope.coronapoker.ios.IOSMusic.create(")
    editable = "    private boolean frontendModalTextFieldActive() {\n"
    if text.count(editable) != 1: raise RuntimeError("Frontend editable modal ownership changed")
    text = text.replace(editable, editable + '''        // Root workflows are drawn as modals too. Their own fields remain
        // editable, while nested confirmations still block underlying fields.
        if (surface == Surface.NEW_GAME && isFrontendRootModal()
                && ("nick".equals(activeField) || "password".equals(activeField)
                    || "server".equals(activeField) || "port".equals(activeField))) return true;
        if (surface == Surface.SETTINGS && isSettingsRootModal()
                && "statsExcludeNicks".equals(activeField)) return true;
''')
    controls = "        if (activeField == null || Character.isISOControl(character)) {"
    if text.count(controls) != 1: raise RuntimeError("Frontend typed input changed")
    text = text.replace(controls, '''        if (activeField != null && character == '\\b')
            return handleActiveFieldKey(Input.Keys.BACKSPACE);
''' + controls)
    keydown = "    public boolean keyDown(int keycode) {\n"
    if text.count(keydown) != 1: raise RuntimeError("Frontend key ownership changed")
    text = text.replace(keydown, keydown + '''        if ((keycode == Input.Keys.ENTER || keycode == Input.Keys.NUMPAD_ENTER)
                && surface == Surface.NEW_GAME && isFrontendRootModal()
                && activeField != null) {
            clearActiveField();
            com.tonikelope.coronapoker.ios.IOSKeyboard.visible(false);
            return true;
        }
''')
    text = replace_method(text, "    private boolean shouldDrawEditMenu(", '''    private boolean shouldDrawEditMenu() {
        return editMenu != null && activeField != null
                && (!hasBlockingFrontendModal() || frontendModalTextFieldActive());
    }''')
    for signature in [
        "    public boolean touchDown(int screenX, int screenY, int pointerIndex, int button) {\n",
        "    public boolean touchUp(int screenX, int screenY, int pointerIndex, int button) {\n",
        "    @Override public boolean touchCancelled(int x, int y, int p, int b) {\n"]:
        if text.count(signature) != 1: raise RuntimeError("Frontend touch ownership changed")
        text = text.replace(signature, signature + "        mobileTextHold.cancel();\n")
    dragged = "    public boolean touchDragged(int screenX, int screenY, int pointerIndex) {\n"
    if text.count(dragged) != 1: raise RuntimeError("Frontend selection drag ownership changed")
    text = text.replace(dragged, dragged + "        mobileTextHold.move(pointerIndex, screenX, screenY);\n")
    capture = "                    pointerSelectionField = field.id;\n"
    if text.count(capture) != 1: raise RuntimeError("Frontend text capture changed")
    text = text.replace(capture, capture + "                    mobileTextHold.begin(pointerIndex, screenX, screenY, System.nanoTime());\n")
    text = text[:text.rfind("}")] + (PORT / "tools/mobile_frontend_input.java.inc").read_text() + "}\n"
    text = text.replace("    public void render() {", "    public void render() {\n        com.tonikelope.coronapoker.ios.IOSKeyboard.visible(activeField != null);\n        com.tonikelope.coronapoker.ios.IOSKeyboard.fit(viewport);\n        mobileUpdateTextHold();")
    emit(original, text)

    original = GDX / "GdxShortcutBindings.java"
    text = original.read_text().replace("import java.awt.event.InputEvent;", "import com.tonikelope.coronapoker.ios.InputEvent;")
    text = text.replace("import java.awt.event.KeyEvent;", "import com.tonikelope.coronapoker.ios.KeyEvent;")
    emit(original, text)
    original = GDX / "GdxGamePresentationSettings.java"
    text = replace_method(original.read_text(), "    static ModMediaCatalog discoverInstalledMod(", '''    static ModMediaCatalog discoverInstalledMod() {
        return ModMediaCatalog.discover(Path.of(System.getProperty("coronapoker.install.dir", System.getProperty("user.home"))));
    }''')
    emit(original, text)

    original = GDX / "GdxUpdateHandoff.java"
    text = replace_method(original.read_text(), "    static Optional<UpdaterService.Request> runtimeRequest(", '''    static Optional<UpdaterService.Request> runtimeRequest(String version, String language) {
        return Optional.empty();
    }''')
    text = replace_method(text, "    static Optional<UpdaterService.ModRequest> runtimeModRequest(", '''    static Optional<UpdaterService.ModRequest> runtimeModRequest(String version, URI downloadUri, String password, String language) {
        return Optional.empty();
    }''')
    emit(original, text)
    (PORT / "validation/game-source-derivation.json").write_text(json.dumps(results, indent=2) + "\n")
    return GENERATED

if __name__ == "__main__": print(prepare())
