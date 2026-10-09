package com.tonikelope.coronapoker.iosvalidation;

import com.tonikelope.coronapoker.CoreGameTableFactory;
import com.tonikelope.coronapoker.core.*;
import com.tonikelope.coronapoker.core.game.*;
import com.tonikelope.coronapoker.core.network.NetworkLobbyGateway;
import com.tonikelope.coronapoker.table.*;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A test driver outside the unchanged 25.79 desktop jars. */
public final class DesktopPeerProbe {
    private static final AtomicInteger endedHands = new AtomicInteger();
    private static final AtomicInteger shuffles = new AtomicInteger();
    private static final AtomicLong events = new AtomicLong();
    private static final CompletableFuture<TableSessionSummary> finished = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("mode address port dataDirectory hands seconds");
        boolean host = "host".equals(args[0]);
        if (!host && !"join".equals(args[0])) throw new IllegalArgumentException("mode must be host or join");
        Path home = Path.of(args[3]).toAbsolutePath(); Files.createDirectories(home);
        System.setProperty("user.home", home.toString());
        try (CoronaPokerApplication application = CoronaPokerBootstrap.createApplication(home)) {
            application.start(); application.menuReady();
            DatabaseService db = application.service(DatabaseService.class);
            SqlIdentityTrustStore trust = new SqlIdentityTrustStore(db);
            CoreGameTableFactory factory = new CoreGameTableFactory(db, GameText.keys(),
                    message -> System.err.println(message), GameDialogSink.noop(), GameDecisionSink.noop(),
                    GamePresentationSettings.defaults(), new ClasspathGameCinematicAssets("cinematics/allin"),
                    false, application.service(PreferencesService.class).properties(), trust);
            try (NetworkLobbyGateway gateway = new NetworkLobbyGateway(home.resolve(".coronapoker"), factory,
                    new RecoverableGameRepository(db), trust)) {
                NewGameTableDraft settings = new NewGameTableDraft();
                settings.setThinkSeconds(10); settings.setRebuy(false);
                int hands = Integer.parseInt(args[4]);
                settings.setHandLimit(hands > 0); if (hands > 0) settings.setHandLimitCount(hands);
                NewGameConnectionDraft.Submission connection = new NewGameConnectionDraft.Submission(
                        host ? NewGameConnectionDraft.Mode.CREATE : NewGameConnectionDraft.Mode.JOIN,
                        System.getenv().getOrDefault("CP_DESKTOP_NICK","PC25-QA"), "native-validation", args[1], args[2], null, false, false, null);
                try (LobbySession lobby = gateway.open(new NewGameRequest(connection, host ? settings.snapshot() : null))
                        .get(30, TimeUnit.SECONDS)) {
                    System.out.println("{\"phase\":\"lobby\",\"desktopVersion\":\"25.79\",\"mode\":\""+args[0]+"\"}");
                    if (host) {
                        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                        while (lobby.snapshot().participants().size() < 2) {
                            if (System.nanoTime() >= deadline) throw new TimeoutException("iPad did not join");
                            Thread.sleep(100);
                        }
                        lobby.submit(new LobbyCommand.StartGame()).toCompletableFuture().get(30, TimeUnit.SECONDS);
                    }
                    StringBuilder keys = new StringBuilder();
                    for (LobbyParticipant participant : lobby.snapshot().participants()) {
                        byte[] key = participant.identityPublicKey();
                        if (key != null) keys.append(java.util.Base64.getEncoder().encodeToString(participant.nickname().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                                .append('\t').append(java.util.Base64.getEncoder().encodeToString(key)).append('\n');
                    }
                    Files.writeString(home.resolve("public-identities.tsv"),keys);
                    TableSession table = lobby.tableSession().toCompletableFuture().get(90, TimeUnit.SECONDS);
                    table.attach(new TableRenderer() {
                        @Override public CompletionStage<Void> open(TableSnapshot snapshot) { return CompletableFuture.completedFuture(null); }
                        @Override public CompletionStage<Void> render(TableVisualEvent event) {
                            events.incrementAndGet();
                            if (event instanceof TableVisualEvent.HandBoundary boundary && boundary.phase() == TableVisualEvent.HandBoundary.Phase.END) endedHands.incrementAndGet();
                            if (event instanceof TableVisualEvent.Shuffle shuffle && shuffle.phase() == TableVisualEvent.Shuffle.Phase.FINISH) shuffles.incrementAndGet();
                            // The desktop test participant makes only a free check or a fold.
                            if (event instanceof TableVisualEvent.ActionControls control) {
                                if (control.state().callAction() == ActionControlState.CallAction.CHECK)
                                    table.commands().submit(new TableCommand.CheckOrCall());
                                else if (control.state().foldEnabled()) table.commands().submit(new TableCommand.Fold());
                            }
                            if (event instanceof TableVisualEvent.CloseTable close) finished.complete(close.summary());
                            return CompletableFuture.completedFuture(null);
                        }
                        @Override public void close() { }
                    }).toCompletableFuture().get(30, TimeUnit.SECONDS);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Integer.parseInt(args[5]));
                    while (!finished.isDone()) {
                        System.out.println(status(db));
                        if (System.nanoTime() >= deadline) throw new TimeoutException("Game validation duration exceeded");
                        Thread.sleep(5000);
                    }
                    TableSessionSummary result = finished.get();
                    double stacks = result.balances().stream().mapToDouble(TableSessionSummary.PlayerBalance::finalStack).sum();
                    double buyins = result.balances().stream().mapToDouble(TableSessionSummary.PlayerBalance::totalBuyin).sum();
                    System.out.println("{\"phase\":\"finished\",\"reason\":\""+result.reason()+"\",\"hands\":"+result.handCount()
                            +",\"endedHands\":"+endedHands.get()+",\"shuffles\":"+shuffles.get()+",\"finalStacks\":"+stacks+",\"totalBuyins\":"+buyins+"}");
                    System.out.println(status(db));
                    if (result.reason() != TableSessionSummary.CloseReason.COMPLETED || result.handCount() < 1
                            || Math.abs(stacks-buyins) > 0.001) throw new AssertionError("Game did not finish with conserved funds");
                }
            }
        }
    }
    private static String status(DatabaseService db) throws SQLException {
        long hands = 0, signatures = 0;
        synchronized (db.lock()) {
            try (Statement query = db.connection().createStatement(); ResultSet rows = query.executeQuery("SELECT COUNT(*) FROM hand WHERE end IS NOT NULL AND end > 0")) { rows.next(); hands=rows.getLong(1); }
            try (Statement query = db.connection().createStatement(); ResultSet rows = query.executeQuery("SELECT COUNT(*) FROM action WHERE sig_b64 IS NOT NULL AND sig_b64 != ''")) { rows.next(); signatures=rows.getLong(1); }
        }
        return "{\"phase\":\"table\",\"utcMillis\":"+System.currentTimeMillis()+",\"events\":"+events.get()
                +",\"endedHands\":"+endedHands.get()+",\"shuffles\":"+shuffles.get()+",\"persistedHands\":"+hands+",\"signedActions\":"+signatures+"}";
    }
}
