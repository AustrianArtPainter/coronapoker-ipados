package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.Gdx;
import com.tonikelope.coronapoker.core.*;
import com.tonikelope.coronapoker.ios.IOSDiagnostics;
import com.tonikelope.coronapoker.ios.IOSMusic;
import com.tonikelope.coronapoker.table.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.robovm.apple.foundation.NSAutoreleasePool;

/** Opt-in real-game scenarios. Normal launches create no test room or listener. */
final class IOSGameValidation {
    static final String MODE = System.getenv("CP_IOS_SCENARIO");
    static final boolean ENABLED = "offline".equals(MODE) || "host".equals(MODE) || "join".equals(MODE);
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicLong EVENTS = new AtomicLong(), HANDS = new AtomicLong(), SHUFFLES = new AtomicLong(), PAYOUTS = new AtomicLong();
    private static volatile String phase = "starting", error = "", latest = "{}";
    private static volatile long ticks, backgroundTicks, maximumTickGapNanos;
    private static volatile GdxTableViewState state;
    private static volatile LobbySession lobby;
    private static volatile TableSessionSummary summary;
    private static final ExecutorService EVENT_LOG = Executors.newSingleThreadExecutor(task -> {
        Thread writer = new Thread(task,"coronapoker-ios-game-event-log"); writer.setDaemon(true); return writer;
    });

    static Path home(Path normal) {
        if (!ENABLED) return normal;
        String run = System.getenv("CP_IOS_RUN_ID");
        if (run == null || !run.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Validation run ID is required");
        return normal.resolve("validation-runs").resolve(run);
    }
    static boolean injectOpenRace() {
        return ENABLED && "1".equals(System.getenv("CP_IOS_LIFECYCLE_OPEN_RACE"));
    }
    static void startOnce(GdxApplicationShell shell, NewGameSessionGateway gateway, DatabaseService db) {
        if (!ENABLED || !STARTED.compareAndSet(false, true)) return;
        worker("coronapoker-ios-game-validation", () -> {
            startStatus(db);
            try {
                NewGameTableDraft settings = new NewGameTableDraft();
                settings.setThinkSeconds(10); settings.setRebuy(false);
                settings.setBotDifficulty(NewGameTableDraft.BotDifficulty.EASY);
                int hands = Integer.parseInt(value("CP_IOS_HANDS", "3"));
                settings.setHandLimit(hands > 0); if (hands > 0) settings.setHandLimitCount(hands);
                String address = value("CP_IOS_PEER_ADDRESS", "127.0.0.1");
                var connection = new NewGameConnectionDraft.Submission("join".equals(MODE)
                        ? NewGameConnectionDraft.Mode.JOIN : NewGameConnectionDraft.Mode.CREATE,
                        "IPAD-QA", value("CP_IOS_ROOM_PASSWORD", "native-validation"), address,
                        value("CP_IOS_GAME_PORT", "7250"), null, false, false, null);
                phase = "opening_lobby";
                lobby = gateway.open(new NewGameRequest(connection, "join".equals(MODE) ? null : settings.snapshot())).get(30,TimeUnit.SECONDS);
                CompletableFuture<Void> visible = new CompletableFuture<>();
                Gdx.app.postRunnable(() -> {
                    try { shell.mobileValidationLobby(lobby); visible.complete(null); }
                    catch (Throwable failure) { visible.completeExceptionally(failure); }
                });
                visible.get(30,TimeUnit.SECONDS); phase = "lobby";
                if ("offline".equals(MODE)) {
                    lobby.submit(new LobbyCommand.AddBot()).toCompletableFuture().get(10,TimeUnit.SECONDS);
                    lobby.submit(new LobbyCommand.AddBot()).toCompletableFuture().get(10,TimeUnit.SECONDS);
                }
                if (!"join".equals(MODE)) {
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                    while (lobby.snapshot().participants().size() < 2) {
                        if (System.nanoTime() >= deadline) throw new TimeoutException("Desktop peer did not join");
                        Thread.sleep(100);
                    }
                    lobby.submit(new LobbyCommand.StartGame()).toCompletableFuture().get(30,TimeUnit.SECONDS);
                }
                TableSession table = lobby.tableSession().toCompletableFuture().get(90,TimeUnit.SECONDS);
                phase = "table";
                int backgroundSeconds = Integer.parseInt(value("CP_IOS_STOP_AFTER_BACKGROUND_SECONDS", "0"));
                if (backgroundSeconds > 0 && !"join".equals(MODE)) {
                    // QA-only graceful cleanup. The normal game and its timeout
                    // strategy are unchanged; use the original host command.
                    worker("coronapoker-ios-validation-last-hand", () -> {
                        try {
                            while (!GdxTableRenderer.background() && summary == null) Thread.sleep(100);
                            if (summary != null) return;
                            Thread.sleep(TimeUnit.SECONDS.toMillis(backgroundSeconds));
                            if (summary == null) table.commands().submit(new TableCommand.SetLastHand(true));
                        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                        catch (Throwable failure) { failed(failure); }
                    });
                }
            } catch (Throwable failure) { failed(failure); }
        });
    }
    static void event(TableVisualEvent event, GdxTableViewState projection) {
        if (!ENABLED) return;
        state = projection.mobileCopy(); EVENTS.incrementAndGet();
        if (event instanceof TableVisualEvent.HandBoundary boundary && boundary.phase() == TableVisualEvent.HandBoundary.Phase.END) HANDS.incrementAndGet();
        if (event instanceof TableVisualEvent.Shuffle shuffle && shuffle.phase() == TableVisualEvent.Shuffle.Phase.FINISH) SHUFFLES.incrementAndGet();
        if (event instanceof TableVisualEvent.Payout || event instanceof TableVisualEvent.PayoutBatch) PAYOUTS.incrementAndGet();
        if (event instanceof TableVisualEvent.CloseTable close) { summary = close.summary(); phase = "closed"; }
        String kind = event.getClass().getSimpleName();
        String details = event instanceof TableVisualEvent.PlayerAction action
                ? ",\"player\":"+quote(action.nickname())+",\"action\":"+quote(action.kind().name())
                : event instanceof TableVisualEvent.PreparationStatus preparation ? ",\"phase\":"+quote(preparation.phase().name()) : "";
        String line = "{\"utcMillis\":" + System.currentTimeMillis() + ",\"sequence\":" + event.sequence()
                + ",\"type\":" + quote(kind) + ",\"background\":" + GdxTableRenderer.background() + details + "}\n";
        // The dealer interrupts its worker during normal cleanup. File I/O must
        // not inherit that interrupt and falsely fail an otherwise finished game.
        EVENT_LOG.execute(() -> {
            try { Files.write(IOSDiagnostics.documents().resolve("ios-game-events.jsonl"), line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
            catch (Throwable failure) { failed(failure); }
        });
    }
    static void failed(Throwable failure) {
        error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        IOSDiagnostics.failure(failure);
    }
    private static void startStatus(DatabaseService db) {
        worker("coronapoker-ios-game-ticks", () -> {
            long previous = System.nanoTime();
            while (true) {
                try (NSAutoreleasePool pool = new NSAutoreleasePool()) {
                    long now = System.nanoTime(); maximumTickGapNanos = Math.max(maximumTickGapNanos, now - previous); previous = now;
                    ticks++; if (GdxTableRenderer.background()) backgroundTicks++;
                    latest = status(db); IOSDiagnostics.report("ios-game-status.json",latest);
                } catch (Throwable failure) { failed(failure); }
                try { Thread.sleep(1000); } catch (InterruptedException finished) { return; }
            }
        });
        worker("coronapoker-ios-game-status-listener", () -> {
            try (ServerSocket server = new ServerSocket(Integer.parseInt(value("CP_IOS_STATUS_PORT", "7235")))) {
                while (true) try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000); InputStream input = socket.getInputStream();
                    StringBuilder command = new StringBuilder();
                    for (int c; command.length() < 64 && (c=input.read()) != -1 && c!='\n'; ) if(c!='\r') command.append((char)c);
                    if ("STATUS".contentEquals(command)) socket.getOutputStream().write((latest+"\n").getBytes(StandardCharsets.UTF_8));
                } catch (IOException failure) { System.err.println("Validation status client: " + failure.getClass().getSimpleName()); }
            } catch (Throwable failure) { failed(failure); }
        });
    }
    private static String status(DatabaseService db) throws SQLException {
        long persistedHands = 0, signedActions = 0;
        synchronized (db.lock()) {
            try (Statement statement = db.connection().createStatement(); ResultSet tables = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='hand'")) {
                if (tables.next() && tables.getLong(1) > 0) {
                    try (Statement query = db.connection().createStatement(); ResultSet rows = query.executeQuery("SELECT COUNT(*) FROM hand WHERE end IS NOT NULL AND end > 0")) { rows.next(); persistedHands=rows.getLong(1); }
                    try (Statement query = db.connection().createStatement(); ResultSet rows = query.executeQuery("SELECT COUNT(*) FROM action WHERE sig_b64 IS NOT NULL AND sig_b64 != ''")) { rows.next(); signedActions=rows.getLong(1); }
                }
            }
        }
        GdxTableViewState view = state; double total = 0; int hand = 0; long sequence = 0;
        String street = "WAITING";
        if (view != null) {
            total = view.snapshot().pot();
            for (var player : view.snapshot().players()) total += player.stack();
            hand=view.handNumber();sequence=view.lastSequence();street=view.snapshot().street().name();
        }
        TableSessionSummary result = summary;
        double stacks = result == null ? 0 : result.balances().stream().mapToDouble(TableSessionSummary.PlayerBalance::finalStack).sum();
        double buyins = result == null ? 0 : result.balances().stream().mapToDouble(TableSessionSummary.PlayerBalance::totalBuyin).sum();
        return "{\"kind\":\"native-java-game\",\"runID\":"+quote(value("CP_IOS_RUN_ID", ""))+",\"mode\":"+quote(MODE)
                +",\"utcMillis\":"+System.currentTimeMillis()+",\"phase\":"+quote(phase)+",\"error\":"+quote(error)
                +",\"ticks\":"+ticks+",\"backgroundTicks\":"+backgroundTicks+",\"maximumTickGapSeconds\":"+(maximumTickGapNanos/1_000_000_000d)
                +",\"background\":"+GdxTableRenderer.background()+",\"musicPlaying\":"+IOSMusic.hasAudiblePlayback()
                +",\"projectionSuspended\":"+GdxTableRenderer.suspendedProjection()
                +",\"events\":"+EVENTS.get()+",\"sequence\":"+sequence+",\"hand\":"+hand+",\"street\":"+quote(street)
                +",\"endedHands\":"+HANDS.get()+",\"finishedShuffles\":"+SHUFFLES.get()+",\"payouts\":"+PAYOUTS.get()
                +",\"persistedFinishedHands\":"+persistedHands+",\"persistedSignedActions\":"+signedActions+",\"displayedFundsTotal\":"+total
                +",\"finalStacks\":"+stacks+",\"totalBuyins\":"+buyins+",\"closeReason\":"+quote(result == null ? "" : result.reason().name())
                +",\"lobbyParticipants\":"+(lobby == null ? 0 : lobby.snapshot().participants().size())+"}";
    }
    private static String quote(String text) { return "\""+text.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\""; }
    private static String value(String name,String fallback) { String text=System.getenv(name);return text==null?fallback:text; }
    private static void worker(String name,Runnable job) { Thread thread=new Thread(job,name);thread.setDaemon(true);thread.start(); }
}
