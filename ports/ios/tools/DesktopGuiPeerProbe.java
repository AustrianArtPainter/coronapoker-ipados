package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.ScreenUtils;
import com.tonikelope.coronapoker.core.*;
import com.tonikelope.coronapoker.core.game.ActionControlState;
import com.tonikelope.coronapoker.table.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** External QA driver: runs the unchanged desktop launcher and GDX renderer. */
public final class DesktopGuiPeerProbe {
    private static final AtomicReference<Throwable> failure = new AtomicReference<>();
    private static boolean passed;
    private static long actionSequence = -1;
    private static int lastCapturedHand;

    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("mode address port dataDirectory hands seconds");
        Path home = Path.of(args[3]).toAbsolutePath(); Files.createDirectories(home);
        System.setProperty("user.home",home.toString());
        System.setProperty("coronapoker.install.dir",home.toString());
        Thread driver = new Thread(() -> {
            try { drive(args,home); passed=true; }
            catch (Throwable error) { failure.set(error); error.printStackTrace(); }
            finally { if (Gdx.app != null) Gdx.app.exit(); }
        },"coronapoker-original-desktop-gui-qa");
        driver.setDaemon(true); driver.start();
        GdxLauncher.main(new String[]{"--windowed","--silent"});
        if (failure.get() != null) throw new AssertionError("Original desktop GUI test failed",failure.get());
        if (!passed) throw new AssertionError("Desktop GUI closed before the test completed");
    }

    private static void drive(String[] args,Path home) throws Exception {
        long startupDeadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(2);
        GdxApplicationShell shell;
        while (true) {
            shell=GdxApplicationShell.active();
            if (shell != null && Gdx.app != null) {
                GdxApplicationShell current=shell;
                if (onGL(() -> get(current,"menu") != null && get(current,"startupIntro") == null)) break;
            }
            if (System.nanoTime() > startupDeadline) throw new TimeoutException("Desktop startup did not finish");
            Thread.sleep(100);
        }
        GdxApplicationShell originalShell=shell;
        CoronaPokerApplication application=(CoronaPokerApplication)get(shell,"application");
        DatabaseService db=application.service(DatabaseService.class);
        NewGameSessionGateway gateway=(NewGameSessionGateway)get(shell,"sessionGateway");
        boolean hotJoinHost="host-hotjoin".equals(args[0]);
        boolean host="host".equals(args[0]) || hotJoinHost;
        if (!host && !"join".equals(args[0])) throw new IllegalArgumentException("Unknown mode");
        NewGameTableDraft settings=new NewGameTableDraft(); settings.setThinkSeconds(10); settings.setRebuy(false);
        if(hotJoinHost)settings.setBotDifficulty(NewGameTableDraft.BotDifficulty.EASY);
        int hands=Integer.parseInt(args[4]); settings.setHandLimit(hands>0); if(hands>0)settings.setHandLimitCount(hands);
        String password=System.getenv("CP_DESKTOP_ROOM_PASSWORD");
        if(password==null)password="native-validation";
        var connection=new NewGameConnectionDraft.Submission(host?NewGameConnectionDraft.Mode.CREATE:NewGameConnectionDraft.Mode.JOIN,
                "PC25-GUI-QA",password,args[1],args[2],null,false,false,null);
        try (LobbySession lobby=gateway.open(new NewGameRequest(connection,host?settings.snapshot():null)).get(30,TimeUnit.SECONDS)) {
            var observedVoiceSequences=ConcurrentHashMap.<Long>newKeySet();
            lobby.subscribe(snapshot -> {
                for(LobbyChatMessage message:snapshot.chat()) {
                    if(message.type()!=LobbyChatMessage.Type.VOICE || !observedVoiceSequences.add(message.sequence()))continue;
                    try {
                        byte[] wave=Base64.getDecoder().decode(message.content());
                        boolean valid=com.tonikelope.coronapoker.core.audio.VoiceWavContract.isValid(wave);
                        long duration=com.tonikelope.coronapoker.core.audio.VoiceWavContract.durationMillis(wave);
                        String sha=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wave));
                        String metadata="{\"phase\":\"voice_received\",\"originalDesktopGUI\":true,\"validOriginalWire\":"+valid
                                +",\"bytes\":"+wave.length+",\"durationMillis\":"+duration+",\"waveSHA256\":\""+sha+"\"}";
                        Files.writeString(home.resolve("received-voice-metadata.jsonl"),metadata+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                        System.out.println(metadata);
                        if(!valid)throw new AssertionError("Invalid voice format reached original lobby");
                    } catch(Exception error) { failure.set(error); error.printStackTrace(); }
                }
            });
            onGL(() -> {
                application.sessionOpened(); set(originalShell,"lobby",lobby);
                ((GdxFrontendScreen)get(originalShell,"menu")).openLobby(lobby);
                // This isolated QA window is driven below. Avoid a mouse click
                // adding a bot or starting it before the real iPad arrives.
                Gdx.input.setInputProcessor(null);
                Method await=GdxApplicationShell.class.getDeclaredMethod("awaitTable",LobbySession.class);
                await.setAccessible(true); await.invoke(originalShell,lobby); return null;
            });
            System.out.println("{\"phase\":\"lobby\",\"desktopVersion\":\""+com.tonikelope.coronapoker.core.ApplicationMetadata.VERSION+"\",\"originalDesktopGUI\":true,\"mode\":\""+args[0]+"\"}");
            if(host) {
                if(hotJoinHost) {
                    lobby.submit(new LobbyCommand.AddBot()).toCompletableFuture().get(10,TimeUnit.SECONDS);
                    lobby.submit(new LobbyCommand.StartGame()).toCompletableFuture().get(30,TimeUnit.SECONDS);
                    TableSession running=lobby.tableSession().toCompletableFuture().get(90,TimeUnit.SECONDS);
                    long flopDeadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);
                    while(!onGL(() -> {
                        CoronaPokerGdxTable current=(CoronaPokerGdxTable)get(originalShell,"table");
                        if(current==null)return false;
                        GdxTableViewState view=(GdxTableViewState)get(current,"liveState");
                        if(view.snapshot().street()==TableSnapshot.Street.FLOP
                                && current.hasRestingCommunityCards(3,true))return true;
                        driveHotJoinAction(running,view);
                        return false;
                    })) {
                        if(System.nanoTime()>flopDeadline)throw new TimeoutException("Hot-join host did not reach a resting flop");
                        Thread.sleep(100);
                    }
                    running.commands().submit(new TableCommand.TogglePause());
                    long pauseDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    while(!onGL(() -> {
                        CoronaPokerGdxTable current=(CoronaPokerGdxTable)get(originalShell,"table");
                        return current!=null && ((GdxTableViewState)get(current,"liveState")).snapshot().paused();
                    })) {
                        if(System.nanoTime()>pauseDeadline)throw new TimeoutException("Hot-join host did not pause");
                        Thread.sleep(50);
                    }
                    System.out.println("{\"phase\":\"hotjoin-ready\",\"street\":\"FLOP\",\"paused\":true,\"publicBoardCards\":3}");
                }
                long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);
                while(lobby.snapshot().participants().stream().noneMatch(p ->
                        !p.local() && !p.bot() && "IPAD-QA".equals(p.nickname()))) {
                    if(System.nanoTime()>deadline)throw new TimeoutException("iPad did not join"); Thread.sleep(100);
                }
                if(hotJoinHost) {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(Integer.parseInt(System.getenv().getOrDefault("CP_DESKTOP_HOT_JOIN_PAUSE_SECONDS","25"))));
                    lobby.tableSession().toCompletableFuture().get(10,TimeUnit.SECONDS)
                            .commands().submit(new TableCommand.TogglePause());
                    System.out.println("{\"phase\":\"hotjoin-resumed\",\"actualIPadPeer\":true}");
                } else lobby.submit(new LobbyCommand.StartGame()).toCompletableFuture().get(30,TimeUnit.SECONDS);
            }
            if(lobby.snapshot().participants().stream().noneMatch(p ->
                    !p.bot() && "IPAD-QA".equals(p.nickname())))
                throw new AssertionError("Original desktop test must include the actual iPad peer");
            StringBuilder keys=new StringBuilder();
            for(LobbyParticipant participant:lobby.snapshot().participants()) {
                byte[] key=participant.identityPublicKey();
                if(key!=null)keys.append(Base64.getEncoder().encodeToString(participant.nickname().getBytes(StandardCharsets.UTF_8)))
                        .append('\t').append(Base64.getEncoder().encodeToString(key)).append('\n');
            }
            Files.writeString(home.resolve("public-identities.tsv"),keys);
            int lobbyWaitSeconds=Integer.parseInt(System.getenv().getOrDefault("CP_DESKTOP_LOBBY_WAIT_SECONDS","90"));
            TableSession session=lobby.tableSession().toCompletableFuture().get(lobbyWaitSeconds,TimeUnit.SECONDS);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(Integer.parseInt(args[5]));
            TableSessionSummary summary;
            long nextReport=0;
            while(true) {
                summary=onGL(() -> {
                    CoronaPokerGdxTable table=(CoronaPokerGdxTable)get(originalShell,"table");
                    if(table==null)return null;
                    GdxTableViewState view=(GdxTableViewState)get(table,"liveState");
                    ActionControlState controls=view.actionControls();
                    long sequence=view.actionControlsSequence();
                    boolean callForCheck="1".equals(System.getenv("CP_DESKTOP_CALL_FOR_CHECK"))
                            && controls.callAction()==ActionControlState.CallAction.CALL;
                    if(sequence!=actionSequence && (callForCheck || controls.callAction()==ActionControlState.CallAction.CHECK || controls.foldEnabled())) {
                        actionSequence=sequence;
                        // Only the external desktop QA participant may call a
                        // paid bet, to exercise the iPad's free-check timeout.
                        session.commands().submit(callForCheck || controls.callAction()==ActionControlState.CallAction.CHECK
                                ?new TableCommand.CheckOrCall():new TableCommand.Fold());
                    }
                    if(view.handNumber()>lastCapturedHand && view.handNumber()>1) {
                        capture(home.resolve("desktop-gui-table.png")); lastCapturedHand=view.handNumber();
                    }
                    return (TableSessionSummary)get(table,"finalSummary");
                });
                if(summary!=null)break;
                if(System.nanoTime()>deadline)throw new TimeoutException("Desktop GUI game did not complete");
                if(System.nanoTime()>nextReport) { report(db);nextReport=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); }
                Thread.sleep(200);
            }
            Thread.sleep(1000);
            onGL(() -> {
                capture(home.resolve("desktop-gui-final.png"));
                ((CoronaPokerGdxTable)get(originalShell,"table")).handleFinalSummaryNavigationAction(0);return null;
            });
            double stacks=summary.balances().stream().mapToDouble(TableSessionSummary.PlayerBalance::finalStack).sum();
            double buyins=summary.balances().stream().mapToDouble(TableSessionSummary.PlayerBalance::totalBuyin).sum();
            report(db);
            System.out.println("{\"phase\":\"finished\",\"originalDesktopGUI\":true,\"reason\":\""+summary.reason()
                    +"\",\"hands\":"+summary.handCount()+",\"finalStacks\":"+stacks+",\"totalBuyins\":"+buyins+"}");
            if(summary.reason()!=TableSessionSummary.CloseReason.COMPLETED || summary.handCount()<1 || Math.abs(stacks-buyins)>0.001)
                throw new AssertionError("Desktop GUI game did not complete with conserved funds");
            long cleanup=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(onGL(() -> get(originalShell,"table")!=null)) {
                if(System.nanoTime()>cleanup)throw new TimeoutException("Desktop GUI did not return to menu");Thread.sleep(100);
            }
        }
    }
    private static void driveHotJoinAction(TableSession session,GdxTableViewState view) {
        ActionControlState controls=view.actionControls();
        long sequence=view.actionControlsSequence();
        // This external desktop QA player may call to reach a public flop.
        // The iPad's own original timeout CHECK/FOLD policy stays intact.
        boolean call=controls.callAction()==ActionControlState.CallAction.CALL
                || controls.callAction()==ActionControlState.CallAction.CHECK;
        if(sequence!=actionSequence && (call || controls.foldEnabled())) {
            actionSequence=sequence;
            session.commands().submit(call
                    ?new TableCommand.CheckOrCall():new TableCommand.Fold());
        }
    }
    private static void report(DatabaseService db) throws SQLException {
        synchronized(db.lock()) {
            try(Statement statement=db.connection().createStatement();ResultSet rows=statement.executeQuery("SELECT COUNT(*) FROM hand WHERE end IS NOT NULL AND end > 0")) {
                rows.next();System.out.println("{\"phase\":\"table\",\"utcMillis\":"+System.currentTimeMillis()+",\"persistedHands\":"+rows.getLong(1)+"}");
            }
        }
    }
    private static void capture(Path path) {
        Pixmap pixels=ScreenUtils.getFrameBufferPixmap(0,0,Gdx.graphics.getBackBufferWidth(),Gdx.graphics.getBackBufferHeight());
        try { PixmapIO.writePNG(new com.badlogic.gdx.files.FileHandle(path.toFile()),pixels,-1,true); }
        finally { pixels.dispose(); }
    }
    private static Object get(Object object,String name) throws ReflectiveOperationException {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static void set(Object object,String name,Object value) throws ReflectiveOperationException {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private static <T> T onGL(Callable<T> work) throws Exception {
        CompletableFuture<T> result=new CompletableFuture<>();
        Gdx.app.postRunnable(() -> {try{result.complete(work.call());}catch(Throwable error){result.completeExceptionally(error);}});
        return result.get(15,TimeUnit.SECONDS);
    }
}
