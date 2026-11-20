#!/usr/bin/env python3
"""Generate reviewed mobile variants, leaving all original desktop sources intact."""
import hashlib
import json
import re
from pathlib import Path
from prepare_graphics_sources import card_shell
import prepare_web_sources as web
from prepare_video_sources import prepare_overlay, prepare_search
from prepare_scene_sources import prepare_scene
from prepare_cinematics_sources import prepare_streaming
from prepare_table_queries import prepare as prepare_table_queries
from prepare_table_amounts import prepare as prepare_table_amounts
from prepare_frame_attribution import prepare_frontend as prepare_frontend_attribution
from prepare_frame_attribution import prepare_table as prepare_table_attribution

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

def prepare_shell_validation_entry(source):
    """Do not evaluate the QA-only service lookup on an ordinary frame."""
    original = "            IOSGameValidation.startOnce(this, sessionGateway, application.service(DatabaseService.class));"
    if source.count(original) != 1 or "if (IOSGameValidation.ENABLED)" in source:
        raise RuntimeError("Shell validation entry changed")
    return source.replace(original, "            if (IOSGameValidation.ENABLED)\n    " + original)

def physical_scissors(source, expected):
    marker = "HdpiUtils.glScissor("
    if source.count(marker) != expected: raise RuntimeError("Clip ownership changed; re-audit pixel units")
    # A normal back buffer and the half-resolution modal capture FBO have
    # different pixel bounds. Use the currently bound GL viewport for both.
    return source.replace(marker, "GdxMobileScissor.set(viewport, ")

def compute_method(source, name, card_crypto=False):
    """Move a pure CPU method body behind the shared two-worker admission gate."""
    headers = re.findall(r"^    (?:public|private)(?: static)? [^\n]*?\b" + name + r"\([^(){}]*\)\s*(?:throws [^{}]+)?(?=\{)", source, re.M)
    if not headers:
        raise RuntimeError("Compute entry changed: " + name)
    def wrap(method):
        opening = method.index("{")
        header = method[:opening]
        parameters = header[header.index("(") + 1:header.rindex(")")]
        # Commas inside generic types aren't parameter separators.
        parts, current, depth = [], "", 0
        for character in parameters + ",":
            if character == "<": depth += 1
            if character == ">": depth -= 1
            if character == "," and depth == 0:
                if current.strip(): parts.append(current.strip().split()[-1])
                current = ""
            else: current += character
        arguments = ", ".join(parts)
        private_header = header.replace("public ", "private ", 1).replace(name + "(", "mobileCompute_" + name + "(", 1)
        probe = ""
        if card_crypto:
            mock_returns = {
                "getGenesisDeck": "com.tonikelope.coronapoker.ios.MockCardCrypto.deck(0)",
                "commitment": "com.tonikelope.coronapoker.ios.MockCardCrypto.scalar()",
                "applyCommutativeLock": "deck == null ? null : deck.clone()",
                "arePointsValid": "com.tonikelope.coronapoker.ios.MockCardCrypto.valid(data)",
                "resolveCardIndex": "com.tonikelope.coronapoker.ios.MockCardCrypto.card(unlockedCard)",
            }
            if name in mock_returns:
                probe += "        if (com.tonikelope.coronapoker.ios.MockCardCrypto.ENABLED) return " + mock_returns[name] + ";\n"
            probe += '        com.tonikelope.coronapoker.ios.MockCardCrypto.requireReal("' + name + '");\n'
        wrapper = header + "{\n" + probe + "        return com.tonikelope.coronapoker.core.game.GameAsync.compute(() -> mobileCompute_" + name + "(" + arguments + "));\n    }\n\n"
        return wrapper + private_header + method[opening:]
    for header in headers:
        source = replace_method(source, header, wrap)
    return source

def prepare_table_uppercase(text):
    """Keep the original null short circuit and replace only table casing work."""
    for signature, owner in [("    private String uppercase(", "gameText"),
                             ("    private static String uppercase(", "text")]:
        def cached(method):
            original = "value.toUpperCase(\n                Locale.forLanguageTag(" + owner + ".language()))"
            if method.count(original) != 1:
                raise RuntimeError("Table uppercase ownership changed: " + signature)
            return method.replace(original, "IOSTableUppercaseCache.uppercase(value, " + owner + ".language())")
        text = replace_method(text, signature, cached)
    return text


def prepare_table_readouts(text):
    """Retain pure table readouts; static upstream algorithms remain the oracle."""
    def change(method, before, after, expected=1):
        if method.count(before) != expected:
            raise RuntimeError("Table readout ownership changed: " + before)
        return method.replace(before, after)

    # Copy the original hit decisions mechanically. Only their pure layout
    # queries change; pointer capture, RepeatHit and asynchronous work do not.
    methods = []
    for signature, new_header, before, after in [
        ("    static int hudTarget(", "    private int mobileHudTarget(",
         "localActionLayout(worldWidth)", "mobileReadouts.actions(worldWidth)"),
        ("    static int finalSummaryActionAt(", "    private int mobileFinalSummaryActionAt(",
         "finalSummaryHeaderLayout(width,", "mobileReadouts.summaryHeader(width,"),
        ("    static int finalSummaryPointerTargetAt(", "    private int mobileFinalSummaryPointerTargetAt(",
         "finalSummaryActionAt(width,", "mobileFinalSummaryActionAt(width,")]:
        def copy(method):
            copied = change(method, signature, new_header)
            copied = change(copied, before, after)
            if "mobileFinalSummaryActionAt(" in new_header:
                copied = change(copied, "layout.navButton(index)",
                                "mobileReadouts.summaryNav(width, height, index)")
            methods.append(copied)
            return method
        text = replace_method(text, signature, copy)

    # v27.90 already caches the local action and pre-action geometry.
    text = replace_method(text, "    private void handleLiveTableInput(", lambda method:
        change(method, "hudTarget(pointer.x, pointer.y,", "mobileHudTarget(pointer.x, pointer.y,"))
    for signature in [
        "        public boolean touchDown(int screenX, int screenY, int pointerIndex,",
        "        public boolean touchUp(int screenX, int screenY, int pointerIndex,",
        "    private void drawFinalSummary()"]:
        text = replace_method(text, signature, lambda method:
            change(method, "finalSummaryPointerTargetAt(", "mobileFinalSummaryPointerTargetAt("))
    def summary_paint(method):
        method = change(method, "finalSummaryHeaderLayout(width,",
                        "mobileReadouts.summaryHeader(width,")
        return change(method, "header.navButton(index)",
                      "mobileReadouts.summaryNav(width, height, index)", 2)
    text = replace_method(text, "    private void mobilePaintFinalSummary()", summary_paint)

    for signature in ["    public void dispose()", "    void disposeFailedCreation()"]:
        text = replace_method(text, signature, lambda method:
            method[:method.index("{") + 1] + "\n        mobileReadouts.clear();" + method[method.index("{") + 1:])
    text = prepare_table_uppercase(text)
    return (text[:text.rfind("}")]
            + "    private final IOSTableReadoutCache mobileReadouts = new IOSTableReadoutCache();\n\n"
            + "\n\n".join(methods) + "\n}\n")

def prepare_frontend_text_items(text):
    """Reuse only final immutable menu items; keep fitting and drawing intact."""
    def change(method, before, after):
        if method.count(before) != 1:
            raise RuntimeError("Frontend menu ownership changed: " + before)
        return method.replace(before, after)
    for signature, snapshot, color in [
        ("    private void drawMainMenuQuote(", "new Color(Color.WHITE)", "Color.WHITE"),
        ("    private void pixelSnappedTextInBox(", "new Color(color)", "color"),
        ("    private void text(BitmapFont font,", "new Color(color)", "color"),
        ("    private TextItem fittedTextItem(", "new Color(color)", "color")]:
        def factory(method):
            if method.count("new TextItem(") != 1 or method.count(snapshot) != 1:
                raise RuntimeError("Frontend immutable text ownership changed: " + signature)
            return method.replace("new TextItem(", "mobileTextItem(").replace(snapshot, color)
        text = replace_method(text, signature, factory)
    def menu_actions(method):
        for before, slot in [
            ("() -> openNewGame(NewGameConnectionDraft.Mode.CREATE)", 0),
            ("() -> openNewGame(NewGameConnectionDraft.Mode.JOIN)", 1),
            ("this::openStats", 2), ("this::openScreenshotViewer", 3),
            ("this::openSettings", 4),
            ("this::openAboutDialog", 6), ("this::requestApplicationExit", 7),
            ("this::showUpdatePrompt", 8)]:
            if method.count(before) != 1:
                raise RuntimeError("Main-menu callback ownership changed: " + before)
            method = method.replace(before, "mobileMenuAction(" + str(slot) + ")")
        return method
    text = replace_method(text, "    private void drawMainMenu()", menu_actions)
    text = replace_method(text, "    private void hit(float x,", lambda method:
        change(method, "        Rectangle bounds = clippedSettingsHit(x, y, w, h);", """        if (mobileDrawingMainMenu && !settingsRowsActive) {
            hits.add(mobileMainMenuHit(x, y, w, h, action));
            return;
        }
        Rectangle bounds = clippedSettingsHit(x, y, w, h);"""))
    def version_label(method):
        return change(method, "        TextItem label = fittedTextItem(versionFont,", """        TextItem label = surface == Surface.MENU && !hasVisibleFrontendModal()
                ? mobileMainMenuVersionItem(versionFont, mobileMainMenuVersionLabel(),
                        GdxProductVersionBrand.X, GdxProductVersionBrand.BASELINE_Y,
                        MOBILE_VERSION_COLOR, GdxProductVersionBrand.MAX_WIDTH)
                : fittedTextItem(versionFont,""")
    text = replace_method(text, "    private void drawFrontendVersionLabelTopLayer()", version_label)
    text = replace_method(text, "    private float masterVolume()", '''    private float masterVolume() {
        return mobileMasterVolume(initialProperties.getProperty("master_volume"));
    }''')
    for signature in ["    private void rebuildFonts(", "    public void dispose()"]:
        text = replace_method(text, signature, lambda method:
            method[:method.index("{") + 1] + "\n        mobileClearMenuTextItems();" + method[method.index("{") + 1:])
    return text[:text.rfind("}")] + (PORT / "tools/mobile_frontend_text_cache.java.inc").read_text() + "}\n"

def prepare():
    (PORT / "validation").mkdir(parents=True, exist_ok=True)
    results = []
    def emit(original, text):
        if original.name == "GdxFrontendScreen.java":
            text = web.frontend(text)
            text = text.replace("import javax.imageio.ImageIO;", "import java.awt.event.MouseEvent;\nimport java.awt.event.KeyEvent;")
            text = replace_method(text, "    private static Pixmap decodeAvatarCropPixmap(", '''    private static Pixmap decodeAvatarCropPixmap(Path selected) throws IOException {
        try { return new Pixmap(Gdx.files.absolute(selected.toAbsolutePath().toString())); }
        catch (RuntimeException invalid) { throw new IOException("Unsupported avatar image",invalid); }
    }''')
        if original.name == "CoronaPokerGdxTable.java":
            text = text.replace("active.animation.failed()", "!active.animation.ready()")
            text = text.replace("animation.fullyPreloaded() ? \"preloaded\" : \"stream\"", "\"preloaded\"")
            text = text.replace("animation.preloadedByteCount()", "animation.gpuBytes()")
        if original.name in ("GdxFrontendScreen.java", "CoronaPokerGdxTable.java"):
            text = text.replace("PokerChromium.available()", "PokerVideoPlatformSupport.isAvailable()")
            def web_frame(method):
                head, body = method.split("{", 1)
                return head + "{ IOSWebPanel.beginFrame(); try {" + body[:-1] + "} finally { IOSWebPanel.endFrame(); } }"
            text = replace_method(text, "    public void render()", web_frame)
        text = text.replace("Gdx.audio.newSound(", "com.tonikelope.coronapoker.ios.ProfiledSound.create(")
        source_root = next(parent for parent in original.parents if parent.name == "java")
        target = GENERATED / original.relative_to(source_root)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        results.append({"source": str(original.relative_to(ROOT)),
                        "originalSHA256": hashlib.sha256(original.read_bytes()).hexdigest(),
                        "mobileSHA256": hashlib.sha256(text.encode()).hexdigest()})

    for name in ("GdxRobertRulesWindow", "GdxImageGalleryWindow"):
        path = GDX / (name + ".java")
        emit(path, web.windows(name, path.read_text()))
    # MobiVM's ICU regex engine already implements Unicode properties but
    # rejects Java's UNICODE_CHARACTER_CLASS flag at initialization.
    path = GDX / "GdxImageSearchHistory.java"
    history = path.read_text()
    flag = r'Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS)'
    if history.count(flag) != 1: raise RuntimeError("Recheck gallery whitespace normalization")
    emit(path, history.replace(flag, r'Pattern.compile("[\\p{Z}\\u0009-\\u000D\\u0085]+")'))
    path = GDX / "PokerStatsBrowser.java"
    emit(path, web.stats(path.read_text()))
    path = GDX / "PokerImageGalleryBrowser.java"
    emit(path, web.gallery(path.read_text()))
    for name in ("GdxTextKeyRepeat","GdxVolumeKeyRepeat"):
        path = GDX / (name + ".java")
        source = clean_imports(path.read_text(), ["com.badlogic.gdx.backends.lwjgl3."])
        emit(path, re.sub(r"!\(Gdx.graphics instanceof Lwjgl3Graphics desktop\)\s*\|\| desktop.getWindow\(\).isFocused\(\)",
                          "!GdxTableRenderer.suspendedProjection()", source))
    preview = GDX / "GdxScreenshotPreview.java"
    source = clean_imports(preview.read_text(), ["org.lwjgl.stb."])
    start = source.index("            int layout = switch")
    fallback = source.index("                resized.setBlending", start)
    end = source.index("            }\n",fallback)
    emit(preview, source[:start] + source[fallback:end] + source[end+14:])
    card = GDX / "GdxCardShell.java"
    emit(card, card_shell(card.read_text()))
    shadows = GDX / "GdxFlyingChipShadows.java"
    emit(shadows, clean_imports(shadows.read_text(), ["org.lwjgl."]).replace("GL11C.glIsEnabled", "Gdx.gl.glIsEnabled"))
    video = GDX / "PokerVideoOverlay.java"
    emit(video, prepare_overlay(video.read_text(), replace_method))
    search = GDX / "PokerVideoSearch.java"
    emit(search, prepare_search(search.read_text(), replace_method))
    # v27.90 keeps field elements in canonical limbs across operations. Compile
    # its field and point implementations together without the older substitutions.
    # GameAsync executes protocol orchestration, sleeps and clock deadlines.
    # Pure crypto/odds work instead shares one global CPU pool, including the
    # dealer and independent peer verification queue. Capping the old mixed
    # executor at two would let clock/ACK waits deadlock the computation path.
    original = CORE / "core/game/GameAsync.java"
    text = original.read_text()
    text = text.replace("    static GameAsync standalone() {", '''    static <T> T compute(Callable<T> task) {
        return com.tonikelope.coronapoker.ios.IOSCompute.call(task);
    }

    static GameAsync standalone() {''')
    text = text.replace("executor = Executors.newCachedThreadPool(task -> {", '''executor = new java.util.concurrent.ThreadPoolExecutor(0, 16, 30L,
                    TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(), task -> {''')
    text = text.replace("Thread thread = new Thread(task, prefix", '''Thread thread = new Thread(() -> {
                    com.tonikelope.coronapoker.ios.IOSCompute.applyUtilityPolicy();
                    task.run();
                }, prefix''')
    text = text.replace("                return thread;\n            });", "                return thread;\n            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());")
    text = text.replace("            } catch (RejectedExecutionException closedDuringSubmit) {", '''            } catch (RejectedExecutionException closedDuringSubmit) {
                if (!closed.get()) throw closedDuringSubmit;''')
    emit(original, text)

    for filename, methods in {
        "RistrettoSRA": ["getGenesisDeck", "commitment", "applyCommutativeLock", "lockPoints", "arePointsValid", "resolveCardIndex"],
        "ShuffleCascade": ["decodeDeck", "encodeDeck", "proveStep", "verifyChain", "proveStepWire", "verifyStepWire", "verifyChainWire"],
        "DualLockWire": ["verifyRotationStepWire", "verifyFullChainWire"],
        "RotationProof": ["prove", "verify"],
        "DealChain": ["verify", "extend"],
        "VerifiableUnlock": ["unlockWithProof", "verifyStep", "verifyChain"],
    }.items():
        original = CORE / ("crypto/" + filename + ".java")
        text = original.read_text()
        if filename == "RistrettoSRA":
            for signature, result in {
                "    public static byte[] generateLockScalar()": "com.tonikelope.coronapoker.ios.MockCardCrypto.scalar()",
                "    public static byte[] getUnlockScalar(": "com.tonikelope.coronapoker.ios.MockCardCrypto.scalar()",
                "    public static boolean isValidScalar(": "scalar != null && scalar.length == 32",
            }.items():
                text = replace_method(text, signature, lambda method, result=result:
                    method[:method.index("{")+1] + "\n        if (com.tonikelope.coronapoker.ios.MockCardCrypto.ENABLED) return " + result + ";" + method[method.index("{")+1:])
        for method in methods: text = compute_method(text, method, card_crypto=True)
        emit(original, text)
    original = CORE / "Crupier.java"
    text = compute_method(original.read_text(), "monteCarlo")
    for signature, statement in {
        "    private boolean enviarCartasJugadoresRemotos()": "return mobileMockDeal();",
        "    private int[] cascadeAndDealCommunityPieces(": "return com.tonikelope.coronapoker.ios.MockCardCrypto.reveal(this.local_mega_packet, offset, numCards);",
        "    public static byte[] contextBoundShuffleGenesis(": "return com.tonikelope.coronapoker.ios.MockCardCrypto.deck(0);",
    }.items():
        text = replace_method(text, signature, lambda method, statement=statement:
            method[:method.index("{")+1] + "\n        if (com.tonikelope.coronapoker.ios.MockCardCrypto.ENABLED) " + statement + method[method.index("{")+1:])
    text = text[:text.rfind("}")] + (PORT / "tools/mobile_mock_deal.java.inc").read_text() + "}\n"
    emit(original, text)

    # Bot hand-strength/potential work is CPU work too. Keep its unchanged
    # decision body behind the same two Utility workers as proofs and odds.
    original = CORE / "Bot.java"
    emit(original, compute_method(original.read_text(), "computeRawDecision"))
    original = GDX / "GdxTableViewState.java"
    text = original.read_text()
    declarations = text[:text.index("    GdxTableViewState(TableSnapshot initialState)")]
    declarations = re.sub(r"/\*.*?\*/|//[^\n]*", "", declarations, flags=re.S)
    fields = [entry for entry in re.findall(
        r"private\s+(?:(static)\s+)?(?:(final)\s+)?([\w.<>?, ]+?)\s+(\w+)\s*(?:=[^;]+)?;", declarations, re.S) if not entry[0]]
    if len(fields) != 56 or not {"snapshot", "nanoTime"}.issubset({field[3] for field in fields}):
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
    # Retain upstream prefix/full/loop preparation and worker scheduling.
    # The iOS decoder consumes the compressed bytes directly, including
    # the bundled encrypted preview delivered by a worker-owned stream.
    text = text.replace("ImageInputStream open()", "byte[] open()")
    start = text.index("    private static ImageInputStream cachedInput")
    end = text.index("    private static List<CpuFrame> decodePrefix", start)
    text = text[:start] + '''    static byte[] progressiveInput(InputStream input) throws IOException {
        try (input) { return input.readAllBytes(); }
    }

    private static void decode(DataSource source, String label, int maxWidth, int knownFrameCount,
            int skippedOutputFrames, FrameSize outputSize, Consumer<CpuFrame> consumer,
            BooleanSupplier cancelled) throws IOException {
        if (skippedOutputFrames < 0) throw new IllegalArgumentException("skippedOutputFrames < 0");
        if (cancelled.getAsBoolean()) return;
        byte[] data = source.open();
        var info = com.tonikelope.coronapoker.ios.PortableGif.inspect(data);
        if (knownFrameCount > info.frames().size()) throw new IOException("Truncated GIF: " + label);
        FrameSize size = outputSize != null ? outputSize
                : new FrameSize(info.width(), info.height()).fitWithin(maxWidth, Integer.MAX_VALUE);
        int[] index = {0};
        com.tonikelope.coronapoker.ios.PortableGif.decode(info, data, size.width(), size.height(), frame -> {
            if (index[0]++ >= skippedOutputFrames)
                consumer.accept(new CpuFrame(frame.width(), frame.height(), frame.startMillis(),
                        frame.endMillis(), frame.rgba()));
        }, () -> cancelled.getAsBoolean() || (knownFrameCount > 0 && index[0] >= knownFrameCount), byte[]::new);
    }

''' + text[end:]
    text = re.sub(r"cachedInput\((data|prepared\.data(?:\(\))?|handle\.readBytes\(\)|Files\.readAllBytes\(path\))\)", r"\1", text)
    start = text.index("    static GifTiming inspect(")
    end = text.index("    private static String sanitizeThreadLabel", start)
    text = text[:start] + '''    static GifTiming inspect(byte[] data, String label, int maxWidth) throws IOException {
        var info = com.tonikelope.coronapoker.ios.PortableGif.inspect(data);
        FrameSize size = new FrameSize(info.width(), info.height()).fitWithin(maxWidth, Integer.MAX_VALUE);
        long[] ends = new long[info.frames().size()];
        long elapsed = 0;
        for (int i = 0; i < ends.length; i++) { elapsed += info.frames().get(i).delayMillis(); ends[i] = elapsed; }
        return new GifTiming(size.width(), size.height(), ends, elapsed);
    }

''' + text[end:]
    text = replace_method(text, "    private void upload(CpuFrame frame)", lambda method:
        method[:method.index("{") + 1] + "\n        long mobileStart = com.tonikelope.coronapoker.ios.IOSPerformance.ENABLED ? System.nanoTime() : 0L;\n        try {" + method[method.index("{") + 1:-1] + "\n        } finally { if (com.tonikelope.coronapoker.ios.IOSPerformance.ENABLED) com.tonikelope.coronapoker.ios.IOSPerformance.record(\"gif.upload\", System.nanoTime() - mobileStart); }\n    }")
    text = prepare_streaming(text, replace_method)
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
    completed = "request.completion.whenComplete((unused, error) -> REQUESTS.remove(request));"
    if text.count(completed) != 1:
        raise RuntimeError("Voice request owner changed")
    text = text.replace(completed, """request.completion.whenComplete((unused, error) -> {
            REQUESTS.remove(request);
            if (error != null) com.tonikelope.coronapoker.ios.IOSDiagnostics.voiceFailure(error);
        });""")
    text = text.replace("Gdx.audio.newMusic(", "com.tonikelope.coronapoker.ios.IOSMusic.create(")
    text = replace_method(text, "    private static boolean audioOutputAvailable(", '''    private static boolean audioOutputAvailable() {
        return com.tonikelope.coronapoker.ios.SilentAudio.outputs().length > 0;
    }''')
    emit(original, text)

    original = GDX / "GdxTextToSpeechPlayback.java"
    text = original.read_text().replace("Gdx.audio.newMusic(", "com.tonikelope.coronapoker.ios.IOSMusic.create(")
    emit(original, text)

    original = GDX / "CoronaPokerGdxTable.java"
    text = clean_imports(original.read_text(), ["javax.sound."])
    text = prepare_table_amounts(text)
    intro_rng = "        SplittableRandom random = new SplittableRandom(seed);"
    intro_range = "    private static float randomRange(SplittableRandom random,"
    if text.count(intro_rng) != 1 or text.count(intro_range) != 1:
        raise RuntimeError("Startup random ownership changed; re-audit the native scalar bridge")
    native_rng = "com.tonikelope.coronapoker.ios.IOSIntroRandom"
    text = text.replace(intro_rng, "        " + native_rng + " random = new " + native_rng + "(seed);")
    text = text.replace(intro_range, "    private static float randomRange(" + native_rng + " random,")
    text = text.replace("import java.util.SplittableRandom;\n", "")
    text = text.replace("new SplittableRandom(", "new " + native_rng + "(")
    if "SplittableRandom" in text:
        raise RuntimeError("A new startup random call needs native compatibility review")
    text = physical_scissors(text, 4)
    # A suspended projection has already applied CloseTable before mobileRestore
    # installs its snapshot. Present the same original terminal UI without
    # applying that event a second time. Ordinary event ordering stays strict.
    close_start = "        if (event instanceof TableVisualEvent.CloseTable close) {\n"
    close_end = "        } else if (event instanceof TableVisualEvent.HotJoinState) {"
    if text.count(close_start) != 1 or text.count(close_end) != 1:
        raise RuntimeError("Terminal table presentation ownership changed")
    start = text.index(close_start) + len(close_start)
    end = text.index(close_end, start)
    close_body = text[start:end]
    apply_close = "            liveState.apply(event);\n"
    if close_body.count(apply_close) != 1:
        raise RuntimeError("Terminal projection application changed")
    closing_ui = close_body.replace(apply_close, "")
    closing_ui = "\n".join(line[4:] if line.startswith("    ") else line
                           for line in closing_ui.split("\n"))
    text = text[:start] + apply_close + "            mobileShowClosingSummary(close, barrier);\n" + text[end:]
    text = text[:text.rfind("}")] + '''
    void mobilePresentClosingSummary(TableVisualEvent.CloseTable close,
            CompletableFuture<Void> barrier) {
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(barrier, "barrier");
        if (liveState.lastSequence() < close.sequence()) {
            acceptEvent(close, barrier);
            return;
        }
        if (liveState.lastSequence() != close.sequence()
                || liveState.snapshot().street() != close.terminalStreet()) {
            throw new IllegalStateException("Closing summary does not match the restored projection");
        }
        mobileShowClosingSummary(close, barrier);
    }

    String mobileVisibleScene() { return finalSummary == null ? "table" : "summary"; }

    private void mobileShowClosingSummary(TableVisualEvent.CloseTable close,
            CompletableFuture<Void> barrier) {
''' + closing_ui + "    }\n}\n"
    instant_entry = '        Objects.requireNonNull(barrier, "barrier");'
    text = replace_method(text, "    void acceptEvent(", lambda method:
        method.replace(instant_entry, instant_entry + "\n        if (mobileAcceptInstantCard(event, barrier)) return;", 1))
    text = text.replace("        updateLiveCardFlight();", "        mobileUpdateProbeSounds();\n        updateLiveCardFlight();")
    paint_marker = "        pointerRepeatHits.clear();\n        boolean preparationModal"
    if text.count(paint_marker) != 1: raise RuntimeError("Paint isolation update boundary changed")
    text = text.replace(paint_marker, '''        pointerRepeatHits.clear();
        if (com.tonikelope.coronapoker.ios.TablePaintProbe.skip(
                finalSummary == null && !intro && preparationPhase
                == TableVisualEvent.PreparationStatus.Phase.READY)) {
            // All dealer, audio and animation-clock updates above still run.
            // The diagnostic intentionally clears the canvas instead of
            // submitting table geometry, shaders, text or overlay textures.
            ScreenUtils.clear(BACKGROUND_BOTTOM, true);
            viewport.apply();
            signalReady();
            return;
        }
        boolean preparationModal''')
    text = text[:text.rfind("}")] + (PORT / "tools/mobile_instant_cards.java.inc").read_text() + "}\n"
    text = text[:text.rfind("}")] + (PORT / "tools/mobile_table_draw_probe.java.inc").read_text() + "}\n"
    # Copy the unchanged paint subpasses for measurement; production drawSeats
    # still calls its original method body and does not use these probe copies.
    seat_start = text.index("    private void drawSeats()")
    seat_batch = text.index("        batch.begin();", seat_start)
    seat_batch_end = text.index("        batch.end();", seat_batch) + len("        batch.end();")
    seat_open = text.index("{", seat_start)
    seat_shapes = text[seat_open + 1:seat_batch]
    seat_sprites = text[seat_batch:seat_batch_end]
    text = text[:text.rfind("}")] + "    private void mobileProbeSeatShapes() {" + seat_shapes + "    }\n" \
            + "    private void mobileProbeSeatSprites() {\n" + seat_sprites + "\n    }\n}\n"
    draw_marker = "        signalReady();\n        drawTableScene();"
    if text.count(draw_marker) != 1: raise RuntimeError("Draw measurement boundary changed")
    text = text.replace(draw_marker, "        signalReady();\n        drawTableScene();\n        mobileMeasureTableDraw();")
    marker = "private final GdxTableViewState liveState;"
    if text.count(marker) != 1: raise RuntimeError("Table projection ownership changed")
    text = text.replace(marker, "private GdxTableViewState liveState;")
    text = replace_method(text, "    private static float wavDurationSeconds(", '''    private static float wavDurationSeconds(FileHandle file, float fallback) {
        if (file == null || !file.exists()) return fallback;
        try { return com.tonikelope.coronapoker.ios.PortableWave.durationSeconds(file.readBytes(), fallback); }
        catch (RuntimeException failure) { return fallback; }
    }''')
    text = text.replace("Gdx.audio.newMusic(", "com.tonikelope.coronapoker.ios.IOSMusic.create(")
    # A shuffle is a table-owned, fully uploaded sequence. No decoder or GL
    # texture update survives into its playback/render path.
    text = re.sub(r"CompletableFuture<StreamingGifTextureAnimation.PreparedLooping>(?=\s+(?:shufflePreparation;|preparation = shufflePreparation;))",
                  "CompletableFuture<PreloadedShuffleAnimation>", text)
    text = text.replace("private StreamingGifTextureAnimation.PreparedLooping preparedShuffle;",
                        "private PreloadedShuffleAnimation.Prepared preparedShuffle;")
    text = text.replace("private final class LiveShuffle {\n\n        final StreamingGifTextureAnimation animation;",
                        "private final class LiveShuffle {\n\n        final PreloadedShuffleAnimation animation;")
    text = replace_method(text, "    private StreamingGifTextureAnimation liveShuffleAnimation(", '''    private PreloadedShuffleAnimation liveShuffleAnimation(String deck) {
        if (mobileShuffleCache == null || !mobileShuffleCache.ready()
                || !availableDeck(deck).equals(preparedShuffleDeck)) {
            throw new IllegalStateException("Shuffle must be prepared before START");
        }
        return mobileShuffleCache;
    }''')
    text = replace_method(text, "    private void prepareShuffleAnimation(", lambda method:
        method.replace("StreamingGifTextureAnimation.prepareLooping(", "PreloadedShuffleAnimation.prepareLooping(")
              .replace("CompletableFuture.supplyAsync(", "com.tonikelope.coronapoker.ios.IOSCompute.future(")
              .replace("        preparedShuffle = null;", "        if (mobileShuffleCache != null) mobileShuffleCache.dispose();\n        mobileShuffleCache = null;\n        preparedShuffle = null;"))
    def bounded_shuffle(method):
        start = method.index("        shufflePreparation = ")
        return method[:start] + '''        GifFramePreparation.cancel(shufflePreparation);
        shufflePreparation = GifFramePreparation.start(() -> external == null
                ? Gdx.files.internal(bundled).readBytes()
                : Files.readAllBytes(external), maxWidth, 256L * 1024 * 1024);
    }'''
    text = replace_method(text, "    private void prepareShuffleAnimation(", bounded_shuffle)
    text = replace_method(text, "    private boolean completeShufflePreparation()", '''    private boolean completeShufflePreparation() {
        if (shufflePreparation != null) {
            if (!shufflePreparation.isDone()) return false;
            mobileShuffleCache = shufflePreparation.join();
            shufflePreparation = null;
            preparedShuffle = null;
        }
        return mobileShuffleCache == null || mobileShuffleCache.uploadNext();
    }''')
    text = replace_method(text, "    private void acceptLiveShuffle(", lambda method:
        method.replace("        if (event.phase() == TableVisualEvent.Shuffle.Phase.START) {", '''        if (event.phase() == TableVisualEvent.Shuffle.Phase.START
                && (mobileShuffleCache == null || !mobileShuffleCache.ready()
                || !availableDeck(event.deck()).equals(preparedShuffleDeck))) {
            if (mobilePendingShuffle != null) throw new IllegalStateException("Duplicate pending shuffle START");
            if (!availableDeck(event.deck()).equals(preparedShuffleDeck)
                    || mobileShuffleCache == null && shufflePreparation == null) {
                prepareShuffleAnimation(event.deck());
            }
            mobilePendingShuffle = event;
            mobilePendingShuffleBarrier = barrier;
            return;
        }
        if (event.phase() == TableVisualEvent.Shuffle.Phase.START) {'''))
    text = replace_method(text, "    private void drawLiveShuffleOverlay()", lambda method:
        method.replace("Texture frame =", "com.badlogic.gdx.graphics.g2d.TextureRegion frame ="))
    text = replace_method(text, "    private void updateLiveShuffle()", lambda method:
        method.replace("            if (active.animation != null) active.animation.dispose();", "            // Cached shuffle frames belong to the table, across all hands."))
    text = text.replace("liveShuffle.animation.dispose();", "/* Table owns the cached shuffle. */")
    text = replace_method(text, "    private void cancelShufflePreparation()", lambda method:
        method.replace("        if (preparation != null) preparation.cancel(true);", '''        if (preparation != null) preparation.cancel(true);
        if (mobileShuffleCache != null) { mobileShuffleCache.dispose(); mobileShuffleCache = null; }
        if (mobilePendingShuffleBarrier != null) mobilePendingShuffleBarrier.cancel(false);
        mobilePendingShuffle = null;
        mobilePendingShuffleBarrier = null;'''))
    for signature, name in [
        ("    static String formatAmount(", "alloc.formatAmount"),
        ("    static String formatPlayTime(", "alloc.formatPlayTime"),
        ("    private static String finalSummaryDate(", "alloc.summaryDate"),
        ("    private void drawCentered(", "alloc.drawText"),
        ("    private void drawFittedLeft(", "alloc.drawLeftText"),
        ("    private void drawFinalSummary()", "alloc.finalSummary"),
        ("    public void render()", "alloc.tableFrame"),
        ("    private void drawTableScene()", "alloc.tableScene"),
        ("    private void drawSeats()", "alloc.seats"),
        ("    private void drawLocalHud(", "alloc.localHud"),
        ("    private void drawCardsAndPot(", "alloc.board"),
        ("    private void updateTableChat()", "alloc.chatPoll"),
        ("    private void drawUiLayer()", "alloc.uiLayer"),
        ("    private void drawBackground()", "alloc.background")]:
        def profile_allocations(method):
            opening = method.index("{")
            return method[:opening + 1] + "\n        long mobileAllocation = com.tonikelope.coronapoker.ios.IOSPerformance.beginAllocation();\n        try {" + method[opening + 1:-1] + \
                '\n        } finally { com.tonikelope.coronapoker.ios.IOSPerformance.endAllocation("' + name + '", mobileAllocation); }\n    }'
        text = replace_method(text, signature, profile_allocations)
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
    # MetalANGLE exposes the framebuffer through GLES. Reuse the upstream
    # synchronous capture and its asynchronous PNG writer on this backend.
    text = replace_method(text, "    private void finishScreenshotReadback(", "")
    text = re.sub(r"^.*(?:private final GdxScreenshotReadback |finishScreenshotReadback\((?:false|true)\);|screenshotReadback\.dispose\(\);).*\n", "", text, flags=re.M)
    text = text.replace("screenshotRequested && !screenshotReadback.hasPending()", "screenshotRequested")
    text = re.sub(r"        try \{\n            if \(screenshotReadback\.submit\(file, width, height\)\) return;.*?\n        \}\n(?=        try \{)", "", text, flags=re.S)
    if "screenshotReadback" in text or "ScreenUtils.getFrameBufferPixels(0, 0, width, height, false)" not in text:
        raise RuntimeError("Recheck screenshot readback for the mobile GLES backend")
    # v27.90 already flips the bottom-up framebuffer exactly once in its PNG writer.
    def mobile_capture(writer):
        writer = writer.replace("        Thread writer =", "        long mobileRequest = mobileScreenshotRequest;\n        Thread writer =")
        callback = 'Gdx.app.postRunnable(() -> showTableToast(gameText.translate(\n                        completed ? "gdx.screenshot.saved" : "gdx.screenshot.save_failed")));'
        if writer.count(callback) != 1:
            raise RuntimeError("Screenshot writer completion changed")
        return writer.replace(callback, """Gdx.app.postRunnable(() -> {
                    if (disposed) return;
                    mobileScreenshotWritten(mobileRequest, completed);
                    showTableToast(gameText.translate(completed ? "gdx.screenshot.saved" : "gdx.screenshot.save_failed"));
                });""")
    text = replace_method(text, "    private void writeScreenshot(", mobile_capture)
    for signature in [
        "        public boolean touchDown(int screenX, int screenY, int pointerIndex,\n                int button) {\n",
        "        public boolean touchUp(int screenX, int screenY, int pointerIndex,\n                int button) {\n"]:
        if text.count(signature) != 1: raise RuntimeError("Table touch ownership changed")
        text = text.replace(signature, signature + "            if (pointerIndex != 0) return true;\n            mobileTextHold.cancel();\n")
    dragged = "        public boolean touchDragged(int screenX, int screenY, int pointerIndex) {\n"
    if text.count(dragged) != 1: raise RuntimeError("Table selection drag ownership changed")
    text = text.replace(dragged, dragged + "            if (pointerIndex != 0) return true;\n            mobileTextHold.move(pointerIndex, screenX, screenY);\n")
    chat_hold = "                    chatPointerSelectionDragging = true;\n"
    log_hold = "                gameLogSelectionDragging = true;\n"
    if text.count(chat_hold) != 1 or text.count(log_hold) != 1: raise RuntimeError("Table text capture changed")
    text = text.replace(chat_hold, chat_hold + "                    mobileTextHoldTarget = 1;\n                    mobileTextHold.begin(pointerIndex, screenX, screenY, System.nanoTime());\n")
    text = text.replace(log_hold, log_hold + "                mobileTextHoldTarget = 2;\n                mobileTextHold.begin(pointerIndex, screenX, screenY, System.nanoTime());\n")
    text = replace_method(text, "        public boolean touchDown(int screenX, int screenY, int pointerIndex,", lambda method:
        method.replace("            mobileTextHold.cancel();", "            mobileTextHold.cancel();\n            if (mobileScroll.active() && !mobileScroll.owns(pointerIndex)) return true;\n            mobileUiPressBlocked = false;\n            mobileBeginContentScroll(screenX, screenY, pointerIndex, button);", 1))
    text = replace_method(text, "        public boolean touchDragged(int screenX, int screenY, int pointerIndex)", lambda method:
        method.replace("            mobileTextHold.move(pointerIndex, screenX, screenY);", "            mobileTextHold.move(pointerIndex, screenX, screenY);\n            if (mobileMoveContentScroll(screenX, screenY, pointerIndex)) return true;", 1))
    text = replace_method(text, "        public boolean touchUp(int screenX, int screenY, int pointerIndex,", lambda method:
        method.replace("            mobileTextHold.cancel();", "            mobileTextHold.cancel();\n            if (mobileEndContentScroll(screenX, screenY, pointerIndex)) return true;", 1))
    # 26.16 owns voice and scrollbar gestures through pointer captures. Extend
    # its cancellation path instead of replacing it or adding a second method:
    # releasing the voice capture must still abort recording on interruption.
    text = replace_method(text, "        public boolean touchCancelled(int screenX, int screenY,", lambda method:
        method[:method.index("{") + 1] + '''
            if (pointerIndex != 0) return true;
            mobileTextHold.cancel(); mobileScroll.cancel(); clearPointerRepeat();
            mobileUiPressBlocked = true;
''' + method[method.index("{") + 1:])
    def mobile_ui_input(method):
        marker = "                handleUiClick(pointer.x, pointer.y);"
        if method.count(marker) != 1: raise RuntimeError("Table utility click ownership changed")
        return method.replace(marker, "                if (!mobileUiPressBlocked) handleUiClick(pointer.x, pointer.y);").replace(
            "                if (uiLayer == UI_SETTINGS\n", "                if (!mobileUiPressBlocked && uiLayer == UI_SETTINGS\n")
    text = replace_method(text, "    private void handleInput()", mobile_ui_input)
    # In 26.16 the shell alone owns frame-rate and monitor polling. The table
    # no longer has a desktop refresh setter to override; keep that boundary
    # explicit so a future upstream move cannot silently revive native calls.
    if "updateWindowMonitorRefresh" in text or "Gdx.graphics.getMonitor(" in text:
        raise RuntimeError("Table display ownership changed; re-audit UIKit frame-rate control")
    mobile_table = (PORT / "tools/mobile_table_methods.java.inc").read_text() + (PORT / "tools/mobile_table_scroll.java.inc").read_text() + (PORT / "tools/mobile_shuffle.java.inc").read_text()
    mobile_table = mobile_table.replace("liveShuffle.animation.dispose();", "/* Table owns the cached shuffle. */")
    text = text[:text.rfind("}")] + mobile_table + "}\n"
    text = text.replace("    public void render() {", "    public void render() {\n        if (mobileResumeRequired) GdxTableRenderer.resumeAll();\n        mobilePreparePendingShuffle();\n        com.tonikelope.coronapoker.ios.IOSKeyboard.visible((activeDialog != null && activeDialog.isAutoCall()) || (uiLayer == UI_CHAT && !tableGallery.isOpen() && !blocksTableUtilities(activeDialog)));\n        com.tonikelope.coronapoker.ios.IOSKeyboard.fit(viewport);\n        mobileUpdateTextHold();")
    text = replace_method(text, "    public void render()", lambda method: method[:method.index("{") + 1] + "\n        try {" + method[method.index("{") + 1:-1] + "\n        } finally { if (!mobileScroll.active()) mobileUiPressBlocked = false; }\n    }")
    text = prepare_table_queries(text)
    text = prepare_scene(text, replace_method, PORT)
    text = prepare_table_readouts(text)
    text = prepare_table_attribution(text, replace_method)
    emit(original, text)

    original = GDX / "GdxApplicationShell.java"
    text = replace_method(original.read_text(), "    private void scheduleJvmSplashClose(", "    private void scheduleJvmSplashClose() { splashCloseScheduled = true; }")
    text = clean_imports(text, ["org.lwjgl.opengl."])
    text = replace_method(text, "    static int defaultFramebufferSamples()", """    static int defaultFramebufferSamples() {
        java.nio.IntBuffer value = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(1);
        Gdx.gl.glGetIntegerv(com.badlogic.gdx.graphics.GL20.GL_SAMPLES, value);
        return Math.max(0, value.get(0));
    }""")
    text = replace_method(text, "    private void updateForegroundFrameRate(", '''    private void updateForegroundFrameRate(float delta) {
        int target = presentationSettings.effectiveFrameRateLimit(refreshRate > 0 ? refreshRate : 60);
        if (target != appliedForegroundFps) { com.tonikelope.coronapoker.ios.IOSPlatform.setFrameRate(target); appliedForegroundFps = target; }
    }''')
    text = replace_method(text, "    private void applySelectedVsync(", '''    private void applySelectedVsync(boolean force) {
        // CADisplayLink owns presentation on iPad. Preserve the preference,
        // while UIKit's frame-rate setter above applies the selected cap.
        appliedVsync = presentationSettings.vsyncEnabled();
    }''')
    text = text.replace("        Gdx.app.postRunnable(() -> {\n            CoronaPokerGdxTable current = table;\n            if (current == null) {\n                request.dismiss();", "        GdxMobileDialogs.presentInBackground(request);\n        Gdx.app.postRunnable(() -> {\n            if (request.complete() && !request.isNotice()) return;\n            CoronaPokerGdxTable current = table;\n            if (current == null) {\n                request.dismiss();")
    text = text[:text.rfind("}")] + '''
    void mobileValidationLobby(LobbySession opened) {
        application.sessionOpened(); lobby = opened; menu.openLobby(lobby); awaitTable(lobby);
    }
    @Override public void pause() { PokerVideoRuntime.background(); GdxTableRenderer.suspendAll(); }
    @Override public void resume() {
        Gdx.app.postRunnable(GdxTableRenderer::resumeAll);
    }
''' + "}\n"
    text = text.replace("    public void render() {", "    public void render() {\n        if (IOSFrameEvidence.ENABLED) IOSFrameEvidence.scene(startupIntro != null ? 4 : table == null ? 5 : 0, -1);\n        IOSGameValidation.visibleScene(table != null ? table.mobileVisibleScene() : startupIntro != null ? \"startup\" : \"menu\");\n        if (startupIntro == null && menu != null) {\n            IOSGameValidation.startOnce(this, sessionGateway, application.service(DatabaseService.class));\n            IOSVoiceValidation.startOnce();\n        }")
    text = prepare_shell_validation_entry(text)
    emit(original, text)

    original = GDX / "GdxFrontendScreen.java"
    text = clean_imports(original.read_text(), ["java.awt."])
    text = text.replace("        pokerVideoOverlay.prepareFrame();", "")
    text = physical_scissors(text, 1)
    # Production quote reuse, with the original algorithm retained for exact
    # flag 0. Fonts and the original TextItem/draw path retain their ownership.
    def menu_quote_wrap(method):
        original_wrap = """        List<String> lines = wrapText(smallFont, quote,
                MENU_QUOTE_MAX_WIDTH, MENU_QUOTE_MAX_LINES);"""
        if method.count(original_wrap) != 1:
            raise RuntimeError("Menu quote wrapping ownership changed")
        return method.replace(original_wrap, """        List<String> lines = mobileMenuQuoteWrapCache == null
                ? wrapText(smallFont, quote, MENU_QUOTE_MAX_WIDTH, MENU_QUOTE_MAX_LINES)
                : mobileMenuQuoteLines(smallFont, quote, MENU_QUOTE_MAX_WIDTH, MENU_QUOTE_MAX_LINES);""")
    text = replace_method(text, "    private void drawMainMenuQuote(", menu_quote_wrap)
    def menu_text_scope(method):
        update_text = '''uppercase(gameText.translate("gdx.update.menu_action",
                            updateResult.version()))'''
        if method.count(update_text) != 1:
            raise RuntimeError("Menu update label ownership changed")
        method = method.replace(update_text,
                "mobileMenuCache.updateLabel(gameText, updateResult.version())")
        opening = method.index("{")
        return method[:opening + 1] + '''
        mobileDrawingMainMenu = true;
        try {''' + method[opening + 1:-1] + '''
        } finally {
            mobileDrawingMainMenu = false;
        }
    }'''
    text = replace_method(text, "    private void drawMainMenu()", menu_text_scope)
    text = replace_method(text, "    private String uppercase(", '''    private String uppercase(String value) {
        return mobileMenuCache.uppercase(gameText.language(), value, mobileDrawingMainMenu);
    }''')
    def reuse_hover_state(method):
        original_hover = '''        String hoverKey = buttonHoverKey(label, x, y, w, h);
        float hoverAmount = hoverAnimations.getOrDefault(hoverKey, hoverTarget);
        hoverAmount += (hoverTarget - hoverAmount)
                * Math.min(1f, frameDelta * 13f);
        hoverAnimations.put(hoverKey, hoverAmount);'''
        if method.count(original_hover) != 1:
            raise RuntimeError("Frontend hover animation ownership changed")
        return method.replace(original_hover, '''        String hoverKey = mobileDrawingMainMenu
                ? mobileMenuCache.hoverKey(label, x, y, w, h)
                : buttonHoverKey(label, x, y, w, h);
        Float previousHover = hoverAnimations.get(hoverKey);
        float hoverAmount = previousHover == null ? hoverTarget : previousHover.floatValue();
        hoverAmount += (hoverTarget - hoverAmount)
                * Math.min(1f, frameDelta * 13f);
        if (previousHover == null
                || Float.floatToIntBits(previousHover.floatValue()) != Float.floatToIntBits(hoverAmount))
            hoverAnimations.put(hoverKey, hoverAmount);''')
    text = replace_method(text, '''    private void themedButton(float x, float y, float w, float h,
            String label, ButtonTone tone, Runnable action, boolean enabled,
            BitmapFont labelFont, boolean repeatable)''', reuse_hover_state)
    for signature in ["    private void rebuildFonts(", "    public void dispose()"]:
        text = replace_method(text, signature, lambda method:
            method[:method.index("{") + 1]
            + "\n        if (mobileMenuQuoteWrapCache != null) mobileMenuQuoteWrapCache.clear();"
            + method[method.index("{") + 1:])
    text = replace_method(text, "    public void dispose()", lambda method:
        method[:method.index("{") + 1]
        + "\n        if (mobileMenuCache != null) mobileMenuCache.clear();"
        + method[method.index("{") + 1:])
    text = text[:text.rfind("}")] + '''
    private final IOSFrontendMenuCache mobileMenuCache = new IOSFrontendMenuCache();
    private boolean mobileDrawingMainMenu;
    private final IOSMenuQuoteWrapCache mobileMenuQuoteWrapCache =
            IOSMenuQuoteWrapCache.ENABLED ? new IOSMenuQuoteWrapCache() : null;

    List<String> mobileMenuQuoteLines(BitmapFont font, String quote, float width, int maxLines) {
        String language = gameText.language();
        List<String> lines = mobileMenuQuoteWrapCache.get(font, fontQualityProfile,
                language, quote, width, maxLines);
        if (lines != null) return lines;
        lines = wrapText(font, quote, width, maxLines);
        mobileMenuQuoteWrapCache.put(font, fontQualityProfile,
                language, quote, width, maxLines, lines);
        return lines;
    }
''' + "}\n"
    text = replace_method(text, "    private CompletableFuture<Path> chooseAvatarFile(", '''    private CompletableFuture<Path> chooseAvatarFile(String title) {
        return CompletableFuture.supplyAsync(com.tonikelope.coronapoker.ios.IOSPlatform::pickImage);
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
        text = text.replace(signature, signature + ("        if (p != 0) return true;\n" if "touchCancelled" in signature else "        if (pointerIndex != 0) return true;\n") + "        mobileTextHold.cancel();\n")
    dragged = "    public boolean touchDragged(int screenX, int screenY, int pointerIndex) {\n"
    if text.count(dragged) != 1: raise RuntimeError("Frontend selection drag ownership changed")
    text = text.replace(dragged, dragged + "        if (pointerIndex != 0) return true;\n        mobileTextHold.move(pointerIndex, screenX, screenY);\n")
    capture = "                    pointerSelectionField = field.id;\n"
    if text.count(capture) != 1: raise RuntimeError("Frontend text capture changed")
    text = text.replace(capture, capture + "                    mobileTextHold.begin(pointerIndex, screenX, screenY, System.nanoTime());\n")
    def mobile_frontend_down(method):
        method = method.replace("        pressedHit = null;", "        if (mobileScroll.active() && !mobileScroll.owns(pointerIndex)) return true;\n        pressedHit = null;", 1)
        marker = "        if (editMenu != null) {"
        return method.replace(marker, "        mobileBeginContentScroll(screenX, screenY, pointerIndex, button);\n" + marker, 1)
    text = replace_method(text, "    public boolean touchDown(int screenX, int screenY, int pointerIndex,", mobile_frontend_down)
    text = replace_method(text, "    public boolean touchDragged(int screenX, int screenY, int pointerIndex)", lambda method:
        method.replace("        mobileTextHold.move(pointerIndex, screenX, screenY);", "        mobileTextHold.move(pointerIndex, screenX, screenY);\n        if (mobileMoveContentScroll(screenX, screenY, pointerIndex)) return true;", 1))
    text = replace_method(text, "    public boolean touchUp(int screenX, int screenY, int pointerIndex,", lambda method:
        method.replace("        mobileTextHold.cancel();", "        mobileTextHold.cancel();\n        if (mobileEndContentScroll(screenX, screenY, pointerIndex)) return true;", 1))
    text = replace_method(text, "    @Override public boolean touchCancelled(int x, int y, int p, int b)", lambda method:
        method.replace("        if (p != 0) return true;", "        if (p != 0) return true;\n        mobileScroll.cancel();\n        pointerSelectionField = null;", 1))
    text = replace_method(text, "    private void captureFrontendModalInput()", lambda method:
        method[:method.index("{") + 1] + "\n        if (mobileScroll != null) mobileScroll.cancel();"
        + method[method.index("{") + 1:])
    text = text[:text.rfind("}")] + (PORT / "tools/mobile_frontend_input.java.inc").read_text() + (PORT / "tools/mobile_frontend_scroll.java.inc").read_text() + "}\n"
    text = text.replace("    public void render() {", "    public void render() {\n        if (IOSFrameEvidence.ENABLED) IOSFrameEvidence.scene(surface == Surface.MENU && !hasVisibleFrontendModal() ? 3 : 5, -1);\n        com.tonikelope.coronapoker.ios.IOSKeyboard.visible(activeField != null);\n        com.tonikelope.coronapoker.ios.IOSKeyboard.fit(viewport);\n        mobileUpdateTextHold();")
    text = prepare_frontend_text_items(text)
    text = prepare_frontend_attribution(text, replace_method)
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
