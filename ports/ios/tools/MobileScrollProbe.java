package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.*;
import com.badlogic.gdx.math.*;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import com.tonikelope.coronapoker.ios.*;
import java.lang.reflect.*;
import java.util.*;
import sun.misc.Unsafe;

/** Calls the shipped handlers with touch coordinates; no renderer, native UI, or game session. */
public final class MobileScrollProbe {
    private static final Unsafe MEMORY;
    private static int checks;
    static {
        try { Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); MEMORY = (Unsafe) f.get(null); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static final class TouchViewport extends FitViewport {
        TouchViewport() { super(1920, 1080); setScreenBounds(0, 0, 1920, 1080); }
        @Override public Vector2 unproject(Vector2 v) { return v.set(v.x, 1080 - v.y); }
    }
    private static final class TableViewport extends ExtendViewport {
        TableViewport() { super(1920, 1080); setWorldSize(1920,1080); setScreenBounds(0,0,1920,1080); }
        @Override public Vector2 unproject(Vector2 v) { return v.set(v.x,1080-v.y); }
    }
    public static void main(String[] args) throws Exception {
        Gdx.input = (Input) Proxy.newProxyInstance(Input.class.getClassLoader(), new Class<?>[]{Input.class},
                (p, m, a) -> m.getReturnType() == boolean.class ? false : m.getReturnType() == int.class ? 0 : null);
        TouchScrollGesture gesture = new TouchScrollGesture();
        require(gesture.begin(0, 500, 500, 500, 580), "primary finger rejected");
        require(!gesture.begin(1, 20, 20, 20, 1060), "second finger stole gesture");
        require(!gesture.move(0, 504, 504, 504, 576), "tap jitter became a swipe");
        require(gesture.move(0, 500, 400, 500, 680), "swipe did not begin");
        require(gesture.deltaY() == 100, "first pan delta lost");
        require(!gesture.move(1, 500, 50, 500, 1030), "second finger moved scroll");
        require(gesture.move(0, 500, 350, 500, 730) && gesture.deltaY() == 50, "incremental pan incorrect");
        require(TouchScrollGesture.offset(80, 50, 100) == 100 && TouchScrollGesture.offset(20, -100, 100) == 0, "bounds not clamped");
        gesture.cancel(); require(!gesture.move(0, 500, 200, 500, 880), "cancelled drag survived");

        GdxFrontendScreen front = fixture(GdxFrontendScreen.class, false);
        enumValue(front, "surface", "SETTINGS");
        set(front, "settingsRowScrollMaximum", 1000f);
        rect(front, "settingsRowScrollTrack").set(1700, 200, 28, 500);
        set(front, "settingsRowScrollThumbHeight", 100f);
        front.touchDown(1710, 1080 - 500, 0, Input.Buttons.LEFT);
        require(!get(front, "scrollDrag").toString().equals("NONE"), "root settings modal blocked the scrollbar");
        front.touchUp(0, 0, 1, Input.Buttons.LEFT);
        require(!get(front, "scrollDrag").toString().equals("NONE"), "second finger released primary scrollbar");
        front.touchDragged(1710, 1080 - 250, 0);
        require((float) get(front, "settingsAudioScroll") > 800, "root scrollbar did not drag");
        front.touchCancelled(0, 0, 0, Input.Buttons.LEFT);
        require(get(front, "scrollDrag").toString().equals("NONE"), "cancel left scrollbar captured");
        set(front, "settingsAudioScroll", 0f);
        front.touchDown(1000, 1080 - 400, 0, Input.Buttons.LEFT);
        front.touchDragged(1000, 1080 - 530, 0);
        require((float) get(front, "settingsAudioScroll") == 130, "frontend contents did not follow finger");
        front.touchUp(1000, 1080 - 530, 0, Input.Buttons.LEFT);
        require(!((TouchScrollGesture) get(front, "mobileScroll")).active(), "released frontend swipe remained captured");
        enumValue(front, "surface", "LOBBY");
        set(front, "lobbyChatScrollMaximum", 500f); set(front, "lobbyChatScroll", 200f);
        rect(front, "lobbyChatViewport").set(600, 300, 700, 400);
        pan(front, 900, 450, 900, 550);
        require((float) get(front, "lobbyChatScroll") == 100, "lobby chat direction incorrect");
        enumValue(front, "surface", "STATS"); enumValue(front, "statsPicker", "GAME");
        set(front, "statsPickerScrollMaximum", 500f); rect(front, "statsPickerScrollTrack").set(1380, 190, 10, 648);
        pan(front, 900, 400, 900, 500);
        require((float) get(front, "statsPickerScroll") == 100, "statistics picker did not pan");
        enumValue(front, "statsPicker", "NONE"); set(front, "statsResultScrollMaximum", 500f);
        enumValue(front, "statsMode", "RESPONSE");
        rect(front, "statsResultScrollTrack").set(1828, 128, 18, 485);
        pan(front, 650, 400, 650, 500);
        require((float) get(front, "statsResultScroll") == 100, "statistics results did not pan");
        set(front, "statsChartZoom", 2f); rect(front, "statsChartViewport").set(500, 100, 700, 600);
        pan(front, 800, 400, 750, 500);
        require((float) get(front, "statsChartPanX") > 0 && (float) get(front, "statsChartPanY") > 0, "zoomed chart did not pan both axes");

        CoronaPokerGdxTable table = fixture(CoronaPokerGdxTable.class, true);
        set(table, "uiLayer", CoronaPokerGdxTable.UI_SETTINGS);
        GdxSettingsSession tableSettings=(GdxSettingsSession)get(table,"settingsSession");
        tableSettings.selectTab(tableSettings.sections().indexOf(GdxSettingsContract.Section.SHORTCUTS));
        InputProcessor input = input(table);
        Object rows = invoke(table, "settingsRowScroll", new Class<?>[0]);
        require(rows != null, "shortcut page unexpectedly not scrollable");
        GdxSettingsLayout.PixelRows pixels = (GdxSettingsLayout.PixelRows) invoke(rows, "rows", new Class<?>[0]);
        int y = Math.round(pixels.viewportBottom() + pixels.viewportHeight() / 2);
        Properties prefs = (Properties) get(table, "tableProperties");
        Properties snapshot = new Properties(); snapshot.putAll(prefs);
        input.touchDown(1000, 1080 - y, 0, Input.Buttons.LEFT);
        require((boolean) get(table, "mobileUiPressBlocked"), "swipe candidate was also delivered as a click");
        input.touchDragged(1000, 1080 - y - 100, 0);
        require((float) get(table, "shortcutScroll") > 0, "table settings did not pan");
        input.touchUp(1000, 1080 - y - 100, 0, Input.Buttons.LEFT);
        require(prefs.equals(snapshot), "swipe changed a setting");
        require(!((TouchScrollGesture) get(table, "mobileScroll")).active(), "table release did not end gesture");
        input.touchDown(1000, 1080 - y, 0, Input.Buttons.LEFT);
        input.touchCancelled(1000, 1080 - y, 0, Input.Buttons.LEFT);
        require(!((TouchScrollGesture) get(table, "mobileScroll")).active(), "table cancellation left capture active");
        int trackX = Math.round((float) invoke(rows, "x", new Class<?>[0]));
        input.touchDown(trackX, 1080 - y, 0, Input.Buttons.LEFT);
        require((boolean) get(table, "settingsRowScrollDragging"), "table scrollbar did not capture");
        input.touchUp(0, 0, 1, Input.Buttons.LEFT);
        require((boolean) get(table, "settingsRowScrollDragging"), "second finger released table scrollbar");
        input.touchUp(trackX, 1080 - y, 0, Input.Buttons.LEFT);
        require(!(boolean) get(table, "settingsRowScrollDragging"), "primary table scrollbar failed to release");
        System.out.println("PASS " + checks + " touch-scroll checks: real frontend/table handlers, modal scrollbar, pan directions, tap suppression, bounds, extra finger, release and cancellation");
    }
    private static void pan(Object target, int x, int y, int nx, int ny) throws Exception {
        Vector2 point = (Vector2) get(target, "pointer"); point.set(x, y);
        invoke(target, "mobileBeginContentScroll", new Class<?>[]{int.class,int.class,int.class,int.class}, x,1080-y,0,Input.Buttons.LEFT);
        invoke(target, "mobileMoveContentScroll", new Class<?>[]{int.class,int.class,int.class},nx,1080-ny,0);
        ((TouchScrollGesture) get(target, "mobileScroll")).cancel();
    }
    private static <T> T fixture(Class<T> type, boolean table) throws Exception {
        T object = type.cast(MEMORY.allocateInstance(type));
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true); Class<?> c=f.getType();
            if (c==Rectangle.class) f.set(object,new Rectangle());
            else if (c==Vector2.class) f.set(object,new Vector2());
            else if (c==List.class) f.set(object,new ArrayList<>());
            else if (c==Set.class) f.set(object,new HashSet<>());
            else if (c==Map.class) f.set(object,new HashMap<>());
            else if (c==GdxKeyRepeat.class) f.set(object,new GdxKeyRepeat());
            else if (c==GdxTextEditState.class) f.set(object,new GdxTextEditState());
            else if (c.isEnum()) for(Object v:c.getEnumConstants()) if(v.toString().equals("NONE")) f.set(object,v);
        }
        set(object,"viewport",table?new TableViewport():new TouchViewport()); set(object,"mobileScroll",new TouchScrollGesture());
        set(object,"mobileTextHold",new TouchTextHold()); set(object,table?"tableProperties":"initialProperties",new Properties());
        set(object,"shortcutBindings",new GdxShortcutBindings(new Properties())); set(object,"gameText",new GdxGameText("es"));
        GdxSettingsSession s=new GdxSettingsSession(); s.begin(table?GdxSettingsSession.Context.LIVE_TABLE:GdxSettingsSession.Context.MENU,new Properties());
        s.selectTab(s.sections().indexOf(GdxSettingsContract.Section.AUDIO)); set(object,"settingsSession",s);
        return object;
    }
    private static InputProcessor input(CoronaPokerGdxTable table) throws Exception {
        for(int i=1;i<30;i++) {
            Class<?> c; try{c=Class.forName(table.getClass().getName()+"$"+i);}catch(ClassNotFoundException absent){continue;}
            if(!InputAdapter.class.isAssignableFrom(c))continue;
            Constructor<?> ctor=c.getDeclaredConstructor(CoronaPokerGdxTable.class);ctor.setAccessible(true);
            return (InputProcessor)ctor.newInstance(table);
        }
        throw new AssertionError("No table handler");
    }
    private static Object invoke(Object o,String name,Class<?>[] types,Object... args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    private static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Rectangle rect(Object o,String name)throws Exception{return(Rectangle)get(o,name);}
    private static void enumValue(Object o,String name,String value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);for(Object v:f.getType().getEnumConstants())if(v.toString().equals(value)){f.set(o,v);return;}throw new AssertionError(value);}
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
