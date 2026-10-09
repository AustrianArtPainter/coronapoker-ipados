package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.Application;
import com.badlogic.gdx.utils.Clipboard;
import com.tonikelope.coronapoker.ios.TouchTextHold;
import com.tonikelope.coronapoker.core.NewGameConnectionDraft;
import com.tonikelope.coronapoker.core.NewGameSubmissionCoordinator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import sun.misc.Unsafe;

/** Exercises real input handlers without constructing a renderer or opening a session.
 * Unsafe is confined to this desktop regression probe and is never shipped in the app.
 */
public final class MobileTextInputProbe {
    private static final Unsafe MEMORY;
    private static final List<String> PASSED = new ArrayList<>();
    private static final List<String> FAILED = new ArrayList<>();
    private static boolean controlPressed;
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            MEMORY = (Unsafe) field.get(null);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    public static void main(String[] args) throws Exception {
        Gdx.input = (Input) Proxy.newProxyInstance(Input.class.getClassLoader(), new Class<?>[]{Input.class},
                (proxy, method, arguments) -> method.getName().equals("isKeyPressed")
                        ? controlPressed && ((Integer) arguments[0]) == Input.Keys.CONTROL_LEFT
                        : method.getReturnType() == boolean.class ? false
                        : method.getReturnType() == int.class ? 0 : null);
        String[] clipboardText = {""};
        Clipboard clipboard = new Clipboard() {
            public String getContents() { return clipboardText[0]; }
            public void setContents(String text) { clipboardText[0] = text; }
            public boolean hasContents() { return !clipboardText[0].isEmpty(); }
        };
        Gdx.app = (Application) Proxy.newProxyInstance(Application.class.getClassLoader(), new Class<?>[]{Application.class},
                (proxy, method, arguments) -> method.getName().equals("getClipboard") ? clipboard : null);
        check("new-game nick typed input and iOS deletion", () -> {
            GdxFrontendScreen screen = frontend("nick");
            typed(screen, "iPad牌手");
            require(draft(screen).nickname().equals("iPad牌手"), "root form swallowed typed text");
            screen.keyTyped('\b');
            require(draft(screen).nickname().equals("iPad牌"), "typed backspace did not remove one character");
            GdxKeyRepeat repeat = (GdxKeyRepeat) get(screen, "textDeleteRepeat");
            require(repeat.keycode() == -1, "UIKit deletion incorrectly started held-key repeat");
            screen.keyDown(Input.Keys.BACKSPACE); screen.keyUp(Input.Keys.BACKSPACE);
            require(draft(screen).nickname().equals("iPad"), "physical keyboard deletion stopped working");
            require(repeat.keycode() == -1, "physical deletion was not released");
            screen.keyTyped('$');
            require(draft(screen).nickname().equals("iPad"), "reserved nick character accepted");
        });
        check("password server and numeric port edits", () -> {
            GdxFrontendScreen password = frontend("password"); typed(password, "Ab9"); password.keyTyped('\b');
            require(draft(password).password().equals("Ab"), "password edit failed");
            GdxFrontendScreen server = frontend("server"); draft(server).setServer(""); typed(server, "192.0.2.83");
            server.keyTyped('\b'); require(draft(server).server().equals("192.0.2.8"), "address edit failed");
            GdxFrontendScreen port = frontend("port"); draft(port).setPort(""); typed(port, "72x34"); port.keyTyped('\b');
            require(draft(port).port().equals("723"), "numeric filtering or deletion failed");
        });
        check("selected Unicode text deletes at code-point boundary", () -> {
            GdxFrontendScreen screen = frontend("nick"); draft(screen).setNickname("A\uD83D\uDE00");
            screen.keyTyped('\b'); require(draft(screen).nickname().equals("A"), "emoji split by deletion");
            GdxTextEditState edit = (GdxTextEditState) get(screen, "textEdit");
            edit.selectAll(draft(screen).nickname()); screen.keyTyped('Z');
            require(draft(screen).nickname().equals("Z"), "selection replacement failed");
        });
        check("nested modal blocks underlying nick and allows own name", () -> {
            GdxFrontendScreen screen = frontend("nick");
            setEnum(screen, "presetDialog", "NAME"); typed(screen, "blocked");
            require(draft(screen).nickname().isEmpty(), "nested modal leaked into underlying nick");
            set(screen, "activeField", "presetName"); set(screen, "presetNameDraft", "");
            typed(screen, "Preset"); screen.keyTyped('\b');
            require(get(screen, "presetNameDraft").equals("Prese"), "child dialog name deletion failed");
        });
        check("keyboard Done dismisses field without submitting game", () -> {
            GdxFrontendScreen screen = frontend("nick"); typed(screen, "iPad");
            screen.keyDown(Input.Keys.ENTER);
            require(get(screen, "activeField") == null, "Done left field focused");
            require(!((NewGameSubmissionCoordinator) get(screen, "submissions")).submitting(), "Done unexpectedly submitted a game");
        });
        check("root and child text context menu ownership", () -> {
            GdxFrontendScreen screen = frontend("nick");
            invoke(screen, "openEditMenu", new Class<?>[]{float.class,float.class}, 100f,100f);
            require((Boolean) invoke(screen, "shouldDrawEditMenu", new Class<?>[]{}), "root text context menu hidden");
            setEnum(screen, "presetDialog", "NAME");
            require(!(Boolean) invoke(screen, "shouldDrawEditMenu", new Class<?>[]{}), "nested modal leaked underlying context menu");
            set(screen, "activeField", "presetName");
            require((Boolean) invoke(screen, "shouldDrawEditMenu", new Class<?>[]{}), "child text context menu hidden");
        });
        check("actual copy cut and paste handlers preserve text", () -> {
            GdxFrontendScreen screen = frontend("nick"); typed(screen, "ab");
            controlPressed = true;
            try {
                screen.keyDown(Input.Keys.A); screen.keyDown(Input.Keys.C);
                require(clipboard.getContents().equals("ab"), "selected copy failed");
                screen.keyDown(Input.Keys.X); require(draft(screen).nickname().isEmpty(), "cut failed");
                screen.keyDown(Input.Keys.V); require(draft(screen).nickname().equals("ab"), "paste failed");
            } finally { controlPressed = false; }
        });
        check("touch hold is one-shot; release swipe extra fingers and resume cancel", () -> {
            TouchTextHold hold = new TouchTextHold();
            hold.begin(0,100,100,0);
            require(!hold.ready(100,100,true,500_000_000L), "short tap became context gesture");
            require(hold.ready(100,100,true,650_000_000L), "hold did not trigger");
            require(!hold.ready(100,100,true,900_000_000L), "hold repeated");
            hold.begin(0,100,100,0); hold.move(0,150,100);
            require(!hold.ready(100,100,true,650_000_000L), "swipe became context gesture");
            hold.begin(0,100,100,0);
            require(!hold.ready(100,100,false,650_000_000L), "released finger triggered");
            hold.begin(0,100,100,0); hold.move(1,100,100);
            require(!hold.ready(100,100,true,650_000_000L), "extra finger triggered");
            hold.begin(0,100,100,0);
            require(!hold.ready(100,100,true,15_000_000_000L), "pause replayed stale gesture");
        });
        check("table chat deletion and modal ownership", () -> {
            CoronaPokerGdxTable table = table(); set(table, "uiLayer", CoronaPokerGdxTable.UI_CHAT);
            InputProcessor input = (InputProcessor) get(table, "tableInput");
            typed(input, "Hello"); input.keyTyped('\b');
            require(get(table, "chatDraft").equals("Hell"), "table chat swallowed deletion");
            set(table, "activeDialog", new GdxTableDialog(GdxTableDialog.Kind.CONFIRM, "confirm",
                    com.tonikelope.coronapoker.core.game.GameDialogSink.Icon.NONE, 0, 0));
            input.keyTyped('\b'); input.keyTyped('X');
            require(get(table, "chatDraft").equals("Hell"), "confirmation leaked input into chat");
        });
        check("auto-call amount deletion preserves explicit decision gate", () -> {
            CoronaPokerGdxTable table = table(); InputProcessor input = (InputProcessor) get(table, "tableInput");
            GdxTableDialog dialog = GdxTableDialog.autoCall(true, 12.34); set(table, "activeDialog", dialog);
            input.keyTyped('\b'); require(dialog.amountText().equals("12.3"), "numeric modal swallowed deletion");
            input.keyTyped('x'); require(dialog.amountText().equals("12.3"), "numeric modal accepted non-number");
            require(!dialog.complete(), "editing automatically accepted wagering setting");
            dialog.toggleOptionEnabled(); input.keyTyped('\b');
            require(dialog.amountText().equals("12.3"), "disabled auto-call amount was edited");
        });
        System.out.println("Passed checks: " + PASSED);
        System.out.println("Failed checks: " + FAILED);
        if (!FAILED.isEmpty()) throw new AssertionError(FAILED.size() + " input regression checks failed");
        System.out.println("PASS real generated mobile input handlers: " + PASSED.size() + " checks");
    }

    private static GdxFrontendScreen frontend(String field) throws Exception {
        GdxFrontendScreen screen = (GdxFrontendScreen) MEMORY.allocateInstance(GdxFrontendScreen.class);
        setEnum(screen, "surface", "NEW_GAME"); setEnum(screen, "dropdown", "NONE");
        setEnum(screen, "presetDialog", "NONE"); setEnum(screen, "blindStructureDialog", "NONE");
        set(screen, "submissions", MEMORY.allocateInstance(NewGameSubmissionCoordinator.class));
        set(screen, "connection", NewGameConnectionDraft.from(new Properties(), NewGameConnectionDraft.Mode.CREATE));
        set(screen, "activeField", field); set(screen, "textEdit", new GdxTextEditState());
        set(screen, "textDeleteRepeat", new GdxKeyRepeat());
        return screen;
    }
    private static CoronaPokerGdxTable table() throws Exception {
        CoronaPokerGdxTable table = (CoronaPokerGdxTable) MEMORY.allocateInstance(CoronaPokerGdxTable.class);
        set(table, "chatDraft", ""); set(table, "chatEdit", new GdxTextEditState());
        set(table, "dialogAmountEdit", new GdxTextEditState());
        for (int index = 1; index < 30; index++) {
            Class<?> type;
            try { type = Class.forName(CoronaPokerGdxTable.class.getName() + "$" + index); }
            catch (ClassNotFoundException absent) { continue; }
            if (!InputAdapter.class.isAssignableFrom(type)) continue;
            Constructor<?> constructor = type.getDeclaredConstructor(CoronaPokerGdxTable.class);
            constructor.setAccessible(true); set(table, "tableInput", constructor.newInstance(table)); return table;
        }
        throw new AssertionError("Actual table input adapter not found");
    }
    private static NewGameConnectionDraft draft(GdxFrontendScreen screen) throws Exception {
        return (NewGameConnectionDraft) get(screen, "connection");
    }
    private static void typed(InputProcessor input, String text) { for (char character : text.toCharArray()) input.keyTyped(character); }
    private static void setEnum(Object object, String name, String value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true);
        for (Object constant : field.getType().getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(value)) { field.set(object, constant); return; }
        }
        throw new AssertionError("Enum not found: " + value);
    }
    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static Object invoke(Object object, String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, parameters); method.setAccessible(true);
        return method.invoke(object, arguments);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void check(String name, Checked operation) {
        try { operation.run(); PASSED.add(name); }
        catch (Throwable failure) { FAILED.add(name + ": " + failure); }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
