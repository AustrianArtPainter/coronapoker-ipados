#!/usr/bin/env python3
"""Generate reviewed mobile variants, leaving all original desktop sources intact."""
import hashlib
import json
import re
from pathlib import Path
from prepare_scene_sources import prepare_scene
from prepare_evaluator_sources import prepare_evaluator
from prepare_cinematics_sources import prepare_streaming

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

def compute_method(source, name, card_crypto=False):
    """Move a pure CPU method body behind the shared two-worker admission gate."""
    match = re.search(r"^    (?:public|private)(?: static)? [^\n]*\b" + name + r"\(", source, re.M)
    if not match: raise RuntimeError("Compute entry changed: " + name)
    signature = source[match.start():match.end()]
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
    return replace_method(source, signature, wrap)

def prepare():
    (PORT / "validation").mkdir(parents=True, exist_ok=True)
    results = []
    def emit(original, text):
        text = text.replace("Gdx.audio.newSound(", "com.tonikelope.coronapoker.ios.ProfiledSound.create(")
        source_root = next(parent for parent in original.parents if parent.name == "java")
        target = GENERATED / original.relative_to(source_root)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        results.append({"source": str(original.relative_to(ROOT)),
                        "originalSHA256": hashlib.sha256(original.read_bytes()).hexdigest(),
                        "mobileSHA256": hashlib.sha256(text.encode()).hexdigest()})

    original = CORE / "crypto/Fe25519.java"
    original_text = original.read_text()
    # Keep the legacy reduction helper solely for the upstream white-box
    # adversarial carry test. Production arithmetic uses the pinned BC field.
    helpers = []
    for signature in ["    static long[] carryNormalize(", "    private static long sh(",
                      "    private static long[] addc("]:
        def capture(body):
            helpers.append(body)
            return body
        replace_method(original_text, signature, capture)
    header = original_text[:original_text.index("/**")]
    header += "import org.bouncycastle.math.ec.rfc7748.X25519Field;\n\n"
    text = header + (PORT / "tools/mobile_field.java.inc").read_text()
    text += "\n    // Original legacy helper retained for its upstream white-box regression.\n"
    text += "    private static final long MASK_51 = (1L << 51) - 1;\n"
    text += "\n\n".join(helpers) + "\n}\n"
    emit(original, text)

    original = CORE / "crypto/EdwardsPoint.java"
    text = original.read_text().replace("import java.math.BigInteger;", "import java.math.BigInteger;\nimport org.bouncycastle.math.ec.rfc7748.X25519Field;")
    text = replace_method(text, "    public EdwardsPoint add(", '''    public EdwardsPoint add(EdwardsPoint q) {
        MobilePointScratch p = MOBILE_POINT.get();
        p.load(this); p.add(q); return p.freeze();
    }''')
    text = replace_method(text, "    public EdwardsPoint dbl()", '''    public EdwardsPoint dbl() {
        MobilePointScratch p = MOBILE_POINT.get();
        p.load(this); p.twice(); return p.freeze();
    }''')
    text = replace_method(text, "    public EdwardsPoint scalarMul(", '''    public EdwardsPoint scalarMul(BigInteger s) {
        if (s.signum() < 0) throw new IllegalArgumentException("scalar must be non-negative");
        if (s.signum() == 0) return IDENTITY;
        // Build cached immutable multiples BEFORE borrowing the accumulator:
        // table construction itself uses add(), on this same thread.
        EdwardsPoint[] odd = nafTable();
        MobilePointScratch p = MOBILE_POINT.get();
        int bits = s.bitLength();
        if (p.naf.length < bits + 2) p.naf = new int[bits + 2];
        java.util.Arrays.fill(p.naf, 0, bits + 2, 0);
        int position = 0, carry = 0, length = 0;
        // Read the scalar's bits with a carry instead of allocating one
        // shifted/subtracted BigInteger per bit of the old wNAF ladder.
        while (position < bits || carry != 0) {
            int low = s.testBit(position) ? 1 : 0;
            if (((low + carry) & 1) == 0) {
                carry = (low + carry) >>> 1;
                position++;
                continue;
            }
            int window = carry;
            for (int j = 0; j < 5; j++) if (s.testBit(position + j)) window += 1 << j;
            int digit = window >= 16 ? window - 32 : window;
            p.naf[position] = digit;
            carry = digit < 0 ? 1 : 0;
            length = position + 1;
            position += 5;
        }
        p.identity();
        for (int i = length - 1; i >= 0; i--) {
            p.twice();
            int digit = p.naf[i];
            if (digit != 0) p.add(odd[(Math.abs(digit) - 1) >>> 1], digit < 0);
        }
        return p.freeze();
    }''')
    text = replace_method(text, "    public static EdwardsPoint multiscalarMul(", '''    public static EdwardsPoint multiscalarMul(BigInteger[] scalars, EdwardsPoint[] points) {
        if (scalars == null || points == null || scalars.length != points.length)
            throw new IllegalArgumentException("scalars/points length mismatch");
        int count = points.length;
        if (count == 0) return IDENTITY;
        MobilePointScratch p = MOBILE_POINT.get();
        if (p.scalars.length < count) {
            p.scalars = new BigInteger[count]; p.tables = new EdwardsPoint[count][];
        }
        try {
            int maxBits = 0;
            for (int i = 0; i < count; i++) {
                p.scalars[i] = scalars[i].mod(L);
                maxBits = Math.max(maxBits, p.scalars[i].bitLength());
                p.tables[i] = points[i].windowTable();
            }
            if (maxBits == 0) return IDENTITY;
            p.identity();
            for (int position = ((maxBits + 3) / 4) * 4 - 4; position >= 0; position -= 4) {
                p.twice(); p.twice(); p.twice(); p.twice();
                for (int i = 0; i < count; i++) {
                    BigInteger s = p.scalars[i];
                    int window = (s.testBit(position + 3) ? 8 : 0) | (s.testBit(position + 2) ? 4 : 0)
                            | (s.testBit(position + 1) ? 2 : 0) | (s.testBit(position) ? 1 : 0);
                    if (window != 0) p.add(p.tables[i][window]);
                }
            }
            return p.freeze();
        } finally {
            java.util.Arrays.fill(p.scalars, 0, count, null);
            java.util.Arrays.fill(p.tables, 0, count, null);
        }
    }''')
    text = text[:text.rfind("}")] + (PORT / "tools/mobile_point.java.inc").read_text() + "}\n"
    emit(original, text)

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
    original = ROOT / "modules/coronapoker-core/src/main/java/org/alberta/poker/HandEvaluator.java"
    emit(original, prepare_evaluator(original.read_text(), replace_method))

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
    queue = "                QUEUE);"
    if text.count(queue) != 1: raise RuntimeError("Voice playback completion ownership changed")
    text = text.replace(queue, '''                QUEUE).whenComplete((ignored, failure) -> {
                    if (failure != null) com.tonikelope.coronapoker.ios.IOSDiagnostics.voiceFailure(failure);
                });''')
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
    text = text.replace("CompletableFuture<StreamingGifTextureAnimation.PreparedLooping>",
                        "CompletableFuture<PreloadedShuffleAnimation>")
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
        method.replace("            mobileTextHold.cancel();", "            mobileTextHold.cancel();\n            if (mobileEndContentScroll(screenX, screenY, pointerIndex)) return true;", 1) + '''
        @Override public boolean touchCancelled(int x, int y, int p, int b) {
            if (p != 0) return true;
            if (mobileScroll.active() && !mobileScroll.owns(p)) return true;
            mobileTextHold.cancel(); mobileScroll.cancel(); clearPointerRepeat();
            mobileUiPressBlocked = true;
            chatPointerSelectionDragging = gameLogSelectionDragging = false;
            quickChatScrollDragging = gameLogScrollDragging = false;
            settingsDebugScrollDragging = settingsRowScrollDragging = false;
            feltClickCandidate = false;
            return true;
        }
''')
    def mobile_ui_input(method):
        marker = "                handleUiClick(pointer.x, pointer.y);"
        if method.count(marker) != 1: raise RuntimeError("Table utility click ownership changed")
        return method.replace(marker, "                if (!mobileUiPressBlocked) handleUiClick(pointer.x, pointer.y);").replace(
            "                if (uiLayer == UI_SETTINGS\n", "                if (!mobileUiPressBlocked && uiLayer == UI_SETTINGS\n")
    text = replace_method(text, "    private void handleInput()", mobile_ui_input)
    text = replace_method(text, "    private void updateWindowMonitorRefresh(", '''    private void updateWindowMonitorRefresh(float delta) {
        // UIKit owns the display. iOS exposes no desktop monitor selection.
        detectedRefreshRate = 60;
    }''')
    mobile_table = (PORT / "tools/mobile_table_methods.java.inc").read_text() + (PORT / "tools/mobile_table_scroll.java.inc").read_text() + (PORT / "tools/mobile_shuffle.java.inc").read_text()
    mobile_table = mobile_table.replace("liveShuffle.animation.dispose();", "/* Table owns the cached shuffle. */")
    text = text[:text.rfind("}")] + mobile_table + "}\n"
    text = text.replace("    public void render() {", "    public void render() {\n        if (mobileResumeRequired) GdxTableRenderer.resumeAll();\n        mobilePreparePendingShuffle();\n        com.tonikelope.coronapoker.ios.IOSKeyboard.visible((activeDialog != null && activeDialog.isAutoCall()) || (uiLayer == UI_CHAT && !chatImageMode && !blocksTableUtilities(activeDialog)));\n        com.tonikelope.coronapoker.ios.IOSKeyboard.fit(viewport);\n        mobileUpdateTextHold();")
    text = replace_method(text, "    public void render()", lambda method: method[:method.index("{") + 1] + "\n        try {" + method[method.index("{") + 1:-1] + "\n        } finally { if (!mobileScroll.active()) mobileUiPressBlocked = false; }\n    }")
    text = prepare_scene(text, replace_method, PORT)
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
    text = text.replace("    public void render() {", "    public void render() {\n        IOSGameValidation.visibleScene(table != null ? table.mobileVisibleScene() : startupIntro != null ? \"startup\" : \"menu\");\n        if (startupIntro == null && menu != null) {\n            IOSGameValidation.startOnce(this, sessionGateway, application.service(DatabaseService.class));\n            IOSVoiceValidation.startOnce();\n        }")
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
        text = text.replace(signature, signature + ("        if (p != 0) return true;\n" if "touchCancelled" in signature else "        if (pointerIndex != 0) return true;\n") + "        mobileTextHold.cancel();\n")
    dragged = "    public boolean touchDragged(int screenX, int screenY, int pointerIndex) {\n"
    if text.count(dragged) != 1: raise RuntimeError("Frontend selection drag ownership changed")
    text = text.replace(dragged, dragged + "        if (pointerIndex != 0) return true;\n        mobileTextHold.move(pointerIndex, screenX, screenY);\n")
    capture = "                    pointerSelectionField = field.id;\n"
    if text.count(capture) != 1: raise RuntimeError("Frontend text capture changed")
    text = text.replace(capture, capture + "                    mobileTextHold.begin(pointerIndex, screenX, screenY, System.nanoTime());\n")
    def mobile_frontend_down(method):
        method = method.replace("        pressedHit = null;", "        if (mobileScroll.active() && !mobileScroll.owns(pointerIndex)) return true;\n        pressedHit = null;", 1)
        method = method.replace("(!blockingModal || statsPicker != StatsPicker.NONE)", "(!blockingModal || statsPicker != StatsPicker.NONE || isFrontendRootModal()) && dropdown == Dropdown.NONE && editMenu == null")
        marker = "        if (editMenu != null) {"
        return method.replace(marker, "        mobileBeginContentScroll(screenX, screenY, pointerIndex, button);\n" + marker, 1)
    text = replace_method(text, "    public boolean touchDown(int screenX, int screenY, int pointerIndex,", mobile_frontend_down)
    text = replace_method(text, "    public boolean touchDragged(int screenX, int screenY, int pointerIndex)", lambda method:
        method.replace("        mobileTextHold.move(pointerIndex, screenX, screenY);", "        mobileTextHold.move(pointerIndex, screenX, screenY);\n        if (mobileMoveContentScroll(screenX, screenY, pointerIndex)) return true;", 1))
    text = replace_method(text, "    public boolean touchUp(int screenX, int screenY, int pointerIndex,", lambda method:
        method.replace("        mobileTextHold.cancel();", "        mobileTextHold.cancel();\n        if (mobileEndContentScroll(screenX, screenY, pointerIndex)) return true;", 1))
    for signature in ["    @Override public boolean touchCancelled(int x, int y, int p, int b)", "    private void captureFrontendModalInput()"]:
        guard = "\n        if (p != 0) return true;" if "touchCancelled" in signature else ""
        text = replace_method(text, signature, lambda method: method[:method.index("{") + 1] + guard + "\n        if (mobileScroll != null) mobileScroll.cancel();\n        scrollDrag = ScrollDrag.NONE; pointerSelectionField = null;" + method[method.index("{") + 1:])
    text = text[:text.rfind("}")] + (PORT / "tools/mobile_frontend_input.java.inc").read_text() + (PORT / "tools/mobile_frontend_scroll.java.inc").read_text() + "}\n"
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
