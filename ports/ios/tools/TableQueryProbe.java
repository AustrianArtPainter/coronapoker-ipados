package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.core.LobbyParticipant;
import com.tonikelope.coronapoker.table.TableSnapshot;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Compares compiled upstream method bodies with the actual iOS converter output. */
public final class TableQueryProbe {
    interface Queries {
        boolean actionable(TableSnapshot snapshot, String nickname);
        boolean actionable(TableSnapshot snapshot, String nickname, boolean unavailable);
        boolean kick(String nickname);
        String deck(String name);
        Texture avatar(String name);
    }
    static abstract class State implements Queries {
        boolean tableHost;
        GdxTableViewState liveState;
        Chat tableChat;
        DeckSettings presentationSettings;
        final Texture avatarBot = Texture.BOT, avatarDefault = Texture.DEFAULT;
        final Map<String, Texture> tableAvatarTextures = new HashMap<>();
    }
    // Texture decoding/GL is deliberately outside this host query test. These
    // seams record the unchanged method's load/filter/cache/failure ordering.
    enum TextureFilter { MipMapLinearLinear, Linear }
    record FileHandle(String path) { }
    static final class Gdx {
        static final Files files = new Files();
    }
    static final class Files {
        FileHandle absolute(String path) {
            textureTrace.add("absolute:" + path);
            if (path.endsWith("absolute-failure")) throw new IllegalStateException();
            return new FileHandle(path);
        }
    }
    static final class Texture {
        static final Texture BOT = new Texture("bot"), DEFAULT = new Texture("default");
        final String key;
        Texture(String key) { this.key = key; }
        Texture(FileHandle file, boolean mipMaps) {
            key = file.path();
            textureTrace.add("load:" + key + ":" + mipMaps);
            if (key.endsWith("load-failure")) throw new IllegalArgumentException();
        }
        void setFilter(TextureFilter min, TextureFilter mag) {
            textureTrace.add("filter:" + min + ":" + mag);
            if (key.endsWith("filter-failure")) throw new IllegalStateException();
        }
        @Override public boolean equals(Object other) { return other instanceof Texture texture && key.equals(texture.key); }
        @Override public int hashCode() { return key.hashCode(); }
    }
    // Transport-only fixtures supply the immutable lists consumed by real methods.
    // Snapshot/player/participant/state and OFFICIAL_DECKS are production classes.
    record ChatSnapshot(List<LobbyParticipant> participants) { }
    static final class Chat {
        ChatSnapshot value;
        int reads;
        Runnable onRead;
        Chat(List<LobbyParticipant> participants) { value = new ChatSnapshot(participants); }
        ChatSnapshot snapshot() {
            reads++;
            if (onRead != null) onRead.run();
            return value;
        }
    }
    static final class DeckSettings {
        final List<String> decks;
        int reads;
        DeckSettings(List<String> decks) { this.decks = decks; }
        List<String> availableDecks() { reads++; return decks; }
    }
    private record Outcome(Object value, Class<?> failure) { }
    private record AvatarOutcome(Outcome result, int snapshotReads, List<String> textureCalls) { }
    private static final List<String> textureTrace = new ArrayList<>();
    private static final State original = new OriginalTableQueries(), mobile = new MobileTableQueries();
    private static int checks;
    private static volatile boolean booleanSink;
    private static volatile String textSink;
    private static volatile Texture textureSink;
    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }
    private static Outcome capture(Supplier<?> query) {
        try { return new Outcome(query.get(), null); }
        catch (Throwable failure) { return new Outcome(null, failure.getClass()); }
    }
    private static void same(Outcome left, Outcome right, String detail) {
        check(left.equals(right), detail + ": " + left + " vs " + right);
        if (left.value instanceof String) check(left.value == right.value, detail + " canonical candidate identity");
    }
    private static TableSnapshot.PlayerSnapshot player(String name, int flags) {
        return new TableSnapshot.PlayerSnapshot(name, 20, 0, 0, (flags & 1) != 0,
                (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0,
                0, 0, 0, 0, false, TableSnapshot.Position.NONE, "", "", List.of());
    }
    private static TableSnapshot snapshot(List<TableSnapshot.PlayerSnapshot> players, String turn, boolean paused) {
        return new TableSnapshot(1, "local", TableSnapshot.Street.PREFLOP, 0, turn, paused, players, List.of());
    }
    private static LobbyParticipant participant(String name, boolean local, boolean bot) {
        return new LobbyParticipant(name, null, local, false, bot, true, false, false, -2, -2);
    }
    private static LobbyParticipant avatarParticipant(String name, String path, boolean bot) {
        return new LobbyParticipant(name, path == null ? null : Path.of(path),
                false, false, bot, true, false, false, -2, -2);
    }
    private static void setup(boolean host, TableSnapshot table, List<LobbyParticipant> participants, List<String> decks) {
        GdxTableViewState state = table == null ? null : new GdxTableViewState(table);
        for (State target : new State[]{original, mobile}) {
            target.tableHost = host; target.liveState = state;
            target.tableChat = new Chat(participants);
            target.presentationSettings = decks == null ? null : new DeckSettings(decks);
            target.tableAvatarTextures.clear();
        }
    }
    private static void kick(String nickname) {
        original.tableChat.reads = mobile.tableChat.reads = 0;
        same(capture(() -> original.kick(nickname)), capture(() -> mobile.kick(nickname)), "kick " + nickname);
        check(original.tableChat.reads == mobile.tableChat.reads, "Same snapshot guard/one-read behavior");
        check(original.tableChat.reads <= 1, "One participant snapshot per query");
    }
    private static void actionable() {
        ArrayList<List<TableSnapshot.PlayerSnapshot>> rosters = new ArrayList<>();
        rosters.add(List.of());
        for (int flags = 0; flags < 16; flags++) {
            rosters.add(List.of(player("other", 1), player("target", flags)));
            rosters.add(List.of(player("target", flags), player("target", 1)));
            rosters.add(List.of(player("target", 1), player("target", flags)));
        }
        for (var roster : rosters) for (boolean paused : new boolean[]{false, true})
            for (String turn : new String[]{null, "target", "other", "TARGET"})
                for (String nickname : new String[]{null, "", " \t", "target", "other", "TARGET"}) {
                    TableSnapshot table = snapshot(roster, turn, paused);
                    same(capture(() -> original.actionable(table, nickname)), capture(() -> mobile.actionable(table, nickname)), "actionable flags/duplicates/guard");
                    for (boolean unavailable : new boolean[]{false, true})
                        same(capture(() -> original.actionable(table, nickname, unavailable)),
                             capture(() -> mobile.actionable(table, nickname, unavailable)), "frontend unavailable guard");
                }
        same(capture(() -> original.actionable(null, "target")), capture(() -> mobile.actionable(null, "target")), "null snapshot");
        var duplicate = snapshot(List.of(player("target", 0), player("target", 1)), "target", false);
        check(original.actionable(duplicate, "target") && mobile.actionable(duplicate, "target"), "Any valid duplicate, not first matching nickname");
    }
    private static void kickQueries() {
        for (int flags = 0; flags < 16; flags++) for (boolean host : new boolean[]{false, true})
            for (boolean paused : new boolean[]{false, true}) for (boolean local : new boolean[]{false, true})
                for (boolean bot : new boolean[]{false, true}) {
                    String name = bot ? "$bot" : "target";
                    setup(host, snapshot(List.of(player(name, flags)), name, paused),
                            List.of(participant("other", false, false), participant(name, local, bot)), null);
                    for (String nickname : new String[]{null, "", " \t", name, "missing"}) kick(nickname);
                }
        for (boolean firstLocal : new boolean[]{false, true}) {
            setup(true, snapshot(List.of(player("target", 9)), "target", false),
                    List.of(participant("target", firstLocal, false), participant("target", !firstLocal, false)), null);
            kick("target");
            check(mobile.kick("target") == !firstLocal, "First matching lobby participant wins");
        }
        setup(true, snapshot(List.of(player("target", 1), player("target", 9)), "target", false),
                List.of(participant("target", false, false)), null);
        kick("target"); check(!mobile.kick("target"), "Existing livePlayer first match preserved");
        setup(true, snapshot(List.of(player("target", 9)), "target", false), List.of(), null);
        kick("target"); check(!mobile.kick("target"), "Missing participant is not eligible");
        // Deliberately malformed transport fixtures check original failure and short circuit.
        for (List<LobbyParticipant> list : Arrays.asList(null,
                Arrays.asList((LobbyParticipant) null, participant("target", false, false)),
                Arrays.asList(participant("target", false, false), null))) {
            setup(true, snapshot(List.of(player("target", 9)), "target", false), list, null);
            kick("target");
        }
        setup(false, null, null, null);
        original.tableChat.onRead = mobile.tableChat.onRead = () -> { throw new AssertionError("Guard touched chat"); };
        kick("target");
        original.tableHost = mobile.tableHost = true;
        kick("target");
        setup(true, snapshot(List.of(player("target", 9)), "target", true), null, null);
        original.tableChat.value = mobile.tableChat.value = null;
        kick(null); kick("missing"); kick("target");
        original.tableChat = mobile.tableChat = null;
        same(capture(() -> original.kick("target")), capture(() -> mobile.kick("target")), "null chat guard");
        setup(true, snapshot(List.of(player("target", 9)), "target", true), List.of(participant("target", false, false)), null);
        check(original.kick("target") && mobile.kick("target"), "Paused table does not invent a new kick guard");
        original.tableChat.onRead = () -> original.tableHost = false;
        mobile.tableChat.onRead = () -> mobile.tableHost = false;
        kick("target"); check(!mobile.kick("target"), "Re-read current host state after snapshot, no cached eligibility");
        setup(true, snapshot(List.of(player("target", 9)), "target", false), List.of(participant("target", false, false)), null);
        kick("target");
        GdxTableViewState changed = new GdxTableViewState(snapshot(List.of(player("target", 1)), "target", false));
        original.liveState = mobile.liveState = changed;
        kick("target"); check(!mobile.kick("target"), "Current timeout state read on every query");
    }
    private static void decks() {
        List<List<String>> lists = Arrays.asList(null, List.of(), List.of("Custom", "goliat"),
                List.of("GOLIAT", "goliat", "custom"), List.of("custom", "CuStOm"), List.of("CuStOm", "custom"),
                List.of("İ", "i", "I", "ı"), Arrays.asList(null, "custom"), Arrays.asList("custom", null));
        for (List<String> available : lists) {
            setup(false, null, List.of(), available);
            for (String deck : new String[]{null, "", "CUSTOM", "goliat", "GOLIAT", "coronapoker", "GOLIAT4", "INTERSTATE60", "missing", "i", "İ", "ı"}) {
                if (original.presentationSettings != null) original.presentationSettings.reads = mobile.presentationSettings.reads = 0;
                same(capture(() -> original.deck(deck)), capture(() -> mobile.deck(deck)), "deck priority/case/null " + deck);
                if (original.presentationSettings != null) {
                    check(original.presentationSettings.reads == mobile.presentationSettings.reads, "Same settings read count");
                    check(original.presentationSettings.reads == (deck == null ? 0 : 1), "Null deck guard and one list snapshot");
                }
            }
        }
        String first = new String("CUSTOM");
        setup(false, null, List.of(), List.of(first, "custom", "goliat"));
        check(original.deck("custom") == first && mobile.deck("custom") == first, "First Mod candidate identity and ordering");
        original.presentationSettings = mobile.presentationSettings = new DeckSettings(List.of("Changed"));
        same(capture(() -> original.deck("Changed")), capture(() -> mobile.deck("Changed")), "Current settings list read without cache");
    }
    private static AvatarOutcome avatarOutcome(State query, String name) {
        textureTrace.clear();
        if (query.tableChat != null) query.tableChat.reads = 0;
        Outcome result = capture(() -> query.avatar(name));
        return new AvatarOutcome(result, query.tableChat == null ? 0 : query.tableChat.reads, List.copyOf(textureTrace));
    }
    private static void avatar(String name) {
        AvatarOutcome oldResult = avatarOutcome(original, name), newResult = avatarOutcome(mobile, name);
        check(oldResult.equals(newResult), "Avatar result/load/failure/snapshot parity " + name + ": " + oldResult + " vs " + newResult);
        check(newResult.snapshotReads <= 1, "One immutable participant snapshot per avatar query");
        if (newResult.result.value == Texture.BOT || newResult.result.value == Texture.DEFAULT)
            check(oldResult.result.value == newResult.result.value, "Exact fallback texture identity");
    }
    private static void avatars() {
        for (boolean bot : new boolean[]{false, true}) {
            String nickname = bot ? "$target" : "target";
            setup(false, null, List.of(avatarParticipant(nickname, null, bot)), null);
            for (String name : new String[]{null, "", nickname, "missing", "CoronaBot$missing"}) avatar(name);
            check(mobile.avatar(nickname) == (bot ? Texture.BOT : Texture.DEFAULT), "Matched participant bot status determines fallback");
            for (String failure : new String[]{"absolute-failure", "load-failure", "filter-failure"}) {
                setup(false, null, List.of(avatarParticipant(nickname, "/" + failure, bot)), null);
                avatar(nickname); avatar(nickname);
                check(original.tableAvatarTextures.isEmpty() && mobile.tableAvatarTextures.isEmpty(), "Failed avatar load remains retryable");
            }
        }
        setup(false, null, List.of(avatarParticipant("target", "/loaded-avatar", false)), null);
        avatar("target");
        Texture loaded = mobile.tableAvatarTextures.get("/loaded-avatar");
        check(loaded != null && mobile.avatar("target") == loaded, "Loaded texture reused by identity");
        avatar("target");
        check(textureTrace.isEmpty(), "Cached lookup does not read/decode/filter the file");
        setup(false, null, List.of(avatarParticipant("target", null, false), avatarParticipant("target", "/ignored", false)), null);
        avatar("target"); check(mobile.avatar("target") == Texture.DEFAULT, "First matching participant remains authoritative");
        setup(false, null, List.of(avatarParticipant("target", null, false)), null);
        avatar("target");
        original.tableChat.value = mobile.tableChat.value = new ChatSnapshot(List.of(avatarParticipant("target", "/changed-avatar", false)));
        avatar("target"); check(mobile.avatar("target").key.equals("/changed-avatar"), "Each query sees a newly published snapshot without cross-frame caching");
        setup(false, null, List.of(avatarParticipant("target", "/not-read", false)), null);
        original.tableChat.onRead = () -> original.tableChat.value = new ChatSnapshot(List.of(avatarParticipant("target", null, false)));
        mobile.tableChat.onRead = () -> mobile.tableChat.value = new ChatSnapshot(List.of(avatarParticipant("target", null, false)));
        avatar("target"); check(mobile.avatar("target") == Texture.DEFAULT, "Snapshot read is not skipped");
        for (List<LobbyParticipant> list : Arrays.asList(null, List.<LobbyParticipant>of(),
                Arrays.asList((LobbyParticipant) null, avatarParticipant("target", null, false)),
                Arrays.asList(avatarParticipant("target", null, false), null))) {
            setup(false, null, list, null);
            avatar(null); avatar("target"); avatar("missing");
        }
        original.tableChat = mobile.tableChat = null;
        avatar(null); avatar("target"); avatar("CoronaBot$missing");
        setup(false, null, List.of(), null);
        original.tableChat.value = mobile.tableChat.value = null;
        avatar(null); avatar("target");
    }
    private static long allocation(com.sun.management.ThreadMXBean bean, State query, TableSnapshot table, int operation, int count) {
        long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        for (int index = 0; index < count; index++) {
            switch (operation) {
                case 0 -> booleanSink = query.actionable(table, "target");
                case 1 -> booleanSink = query.kick("target");
                case 2 -> textSink = query.deck("CUSTOM");
                case 3 -> textSink = query.deck("INTERSTATE60");
                case 4 -> textureSink = query.avatar("target");
                default -> throw new AssertionError();
            }
        }
        return bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
    }
    public static void main(String[] args) {
        actionable(); kickQueries(); decks(); avatars();
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        check(bean.isThreadAllocatedMemorySupported(), "Host allocation counter supported");
        bean.setThreadAllocatedMemoryEnabled(true);
        TableSnapshot table = snapshot(List.of(player("other", 1), player("target", 9)), "target", false);
        // Match the nonempty Java 8 backport representation used by actual
        // LobbySnapshot.copyOf: unmodifiableList(Arrays.asList(array)).
        setup(true, table, Collections.unmodifiableList(Arrays.asList(participant("other", false, false), participant("target", false, false))), List.of("Custom"));
        for (int op = 0; op < 5; op++) {
            allocation(bean, original, table, op, 5000); allocation(bean, mobile, table, op, 5000);
        }
        int iterations = 20000;
        StringBuilder allocations = new StringBuilder();
        for (int op = 0; op < 5; op++) {
            long oldBytes = allocation(bean, original, table, op, iterations);
            long newBytes = allocation(bean, mobile, table, op, iterations);
            check(oldBytes > newBytes && oldBytes - newBytes >= iterations * 16L, "Actual method allocation reduction " + op);
            if (op != 1) check(newBytes <= 1024, "No repeated allocation in rewritten pure list query " + op);
            if (op > 0) allocations.append(',');
            allocations.append("{\"operation\":").append(op).append(",\"originalBytes\":").append(oldBytes)
                    .append(",\"mobileBytes\":").append(newBytes).append('}');
        }
        System.out.println("{\"passed\":true,\"checks\":" + checks + ",\"allocationIterations\":" + iterations
                + ",\"allocationEscapeAnalysis\":false,\"allocationOperations\":[\"actionable\",\"kick\",\"modDeck\",\"officialFallbackDeck\",\"avatar\"]"
                + ",\"allocation\":[" + allocations + "]"
                + ",\"scope\":\"Compiled actual upstream and converter methods; real player/snapshot/participant/state/official decks, transport-only list fixtures and traced texture/GL seams; unchanged livePlayer iterator may remain. Host allocations are not iPad performance measurements.\"}");
    }
}
