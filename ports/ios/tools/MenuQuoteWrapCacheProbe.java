package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.sun.management.ThreadMXBean;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import sun.misc.Unsafe;

/** Runs the generated frontend's original wrapText against actual McLaren metrics. */
public final class MenuQuoteWrapCacheProbe {
    private static final Method WRAP = method("wrapText", BitmapFont.class, String.class, float.class, int.class);
    private static final Method DRAW = method("drawMainMenuQuote", String.class);
    private static final Field CACHE = field("mobileMenuQuoteWrapCache");
    private static final Field GLYPH = field("glyph");
    private static final Field TEXTS = field("texts");
    private static final Field PROFILE = field("fontQualityProfile");
    private static final Field GAME_TEXT = field("gameText");
    private static final Field SMALL_FONT = field("smallFont");
    private static final Field MENU_SCOPE = field("mobileDrawingMainMenu");
    private static final Field TEXT_CACHE = field("mobileMenuTextItems");
    private static final Method TEXT_FACTORY = method("mobileTextItem", BitmapFont.class,
            String.class, float.class, float.class, Color.class, boolean.class,
            boolean.class, float.class, boolean.class);
    private static final MethodHandle TEXT_FACTORY_HANDLE = factoryHandle();
    private static final Unsafe UNSAFE = unsafe();
    private static int textureId, comparisons, textItemComparisons;
    private static volatile Object sink;
    private static volatile float floatSink;

    public static void main(String[] args) throws Exception {
        boolean expectedEnabled = Boolean.parseBoolean(args[1]);
        require(IOSMenuQuoteWrapCache.ENABLED == expectedEnabled, "Only the exact flag value 0 may disable the cache");
        GdxNativesLoader.load();
        Gdx.gl = Gdx.gl20 = (GL20) Proxy.newProxyInstance(GL20.class.getClassLoader(),
                new Class<?>[]{GL20.class}, (p, m, a) -> m.getName().equals("glGenTexture")
                        ? ++textureId : defaultValue(m.getReturnType()));
        Gdx.graphics = (Graphics) Proxy.newProxyInstance(Graphics.class.getClassLoader(),
                new Class<?>[]{Graphics.class}, (p, m, a) -> defaultValue(m.getReturnType()));
        Path resources = Path.of(args[0]);
        FreeTypeFontGenerator generator = new FreeTypeFontGenerator(new FileHandle(
                resources.resolve("fonts/McLaren-Regular.ttf").toFile()));
        try {
            for (float density : new float[]{1f, 1.25f, 2f}) {
                var profile = new GdxAdaptiveFontQuality.Profile(density);
                BitmapFont font = GdxAdaptiveFontQuality.generate(generator, 18, 0f,
                        Color.WHITE, new Color(0x02050ccc),
                        GdxFrontendScreen.FRONTEND_EXTRA_FONT_CHARACTERS, profile);
                try {
                    var screen = screen(profile, font, expectedEnabled);
                    for (String language : new String[]{"en", "es"}) {
                        ((GdxGameText) GAME_TEXT.get(screen)).setLanguage(language);
                        Path quotes = resources.resolve(language.equals("en") ? "quotes_EN.txt" : "quotes_ES.txt");
                        for (String source : Files.readAllLines(quotes)) {
                            if (!source.isBlank()) compareQuote(screen, font, GdxMenuQuotes.format(source), expectedEnabled);
                        }
                    }
                    for (String quote : new String[]{
                            "A short quote", "Árbol, corazón, pingüino y acción — una cita española con acentos.",
                            "Long poker quote with many words to exercise wrapping and truncation. ".repeat(12),
                            "First paragraph\r\nSecond paragraph\rThird paragraph", "first\n\nthird\nfourth",
                            "AnUnbrokenWordThatIsMuchLongerThanTheAvailableWidth".repeat(8), "  \t  "}) {
                        compareQuote(screen, font, quote, expectedEnabled);
                        if (expectedEnabled) for (float width : new float[]{1872f, 280f, 0f})
                            for (int limit : new int[]{0, 1, 2, 4})
                                compareLines(screen, font, quote, width, limit);
                    }
                    immutableTextItems(screen, font, profile, generator);
                    menuInteractionAndVersion(screen, font);
                    if (expectedEnabled) {
                        invalidationAndHits(screen, font, profile, generator);
                        rotationAndLifecycle(screen, font, profile);
                    } else {
                        require(CACHE.get(screen) == null, "Disabled control retained a cache");
                        GLYPH.set(screen, null);
                        expectFailure(() -> DRAW.invoke(screen, "Default still calls the original wrapper"));
                    }
                } finally { font.dispose(); }
            }
        } finally { generator.dispose(); }
        System.out.println("PASS quote cache " + (expectedEnabled ? "enabled" : "disabled") + ": "
                + comparisons + " actual-wrap / TextItem / glyph-vertex comparisons, McLaren densities 1/1.25/2"
                + (expectedEnabled ? "; all keys, no-wrap hits, zero steady-hit allocation, 8s rotation and lifecycle clear" : ""));
        System.out.println("PASS immutable menu text: " + textItemComparisons
                + " original-constructor / actual-fitting / pixel-snap / vertex comparisons;"
                + " exact keys, immutable eviction, scope, lifecycle and zero factory-hit allocation");
    }

    private static GdxFrontendScreen screen(GdxAdaptiveFontQuality.Profile profile, BitmapFont font,
            boolean enabled) throws Exception {
        var screen = (GdxFrontendScreen) UNSAFE.allocateInstance(GdxFrontendScreen.class);
        GLYPH.set(screen, new GlyphLayout());
        TEXTS.set(screen, new ArrayList<>());
        PROFILE.set(screen, profile);
        GAME_TEXT.set(screen, new GdxGameText("en"));
        SMALL_FONT.set(screen, font);
        field("tinyFont").set(screen, font);
        CACHE.set(screen, enabled ? new IOSMenuQuoteWrapCache() : null);
        TEXT_CACHE.set(screen, Array.newInstance(TEXT_CACHE.getType().getComponentType(),
                field("MOBILE_MENU_TEXT_CAPACITY").getInt(null)));
        field("mobileMenuActions").set(screen, new Runnable[10]);
        field("mobileMenuHits").set(screen, Array.newInstance(field("mobileMenuHits").getType().getComponentType(), 16));
        field("hits").set(screen, new ArrayList<>());
        field("surface").set(screen, enumValue(field("surface").getType(), "MENU"));
        field("menuRevealStartedAt").setFloat(screen, Float.NaN);
        var viewport = new FitViewport(1920, 1080);
        viewport.setScreenBounds(3, 5, 1366, 768);
        field("viewport").set(screen, viewport);
        field("identityTransform").set(screen, new Matrix4());
        field("italicTransform").set(screen, new Matrix4());
        var batch = (CaptureBatch) UNSAFE.allocateInstance(CaptureBatch.class);
        batch.values = new ArrayList<>();
        field("batch").set(screen, batch);
        return screen;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String value) { return Enum.valueOf((Class) type, value); }

    @SuppressWarnings("unchecked")
    private static void menuInteractionAndVersion(GdxFrontendScreen screen, BitmapFont font) throws Exception {
        Method actions = method("mobileMenuAction", int.class);
        var other = (GdxFrontendScreen)UNSAFE.allocateInstance(GdxFrontendScreen.class);
        field("mobileMenuActions").set(other, new Runnable[10]);
        for (int i=0;i<10;i++) {
            Object action = actions.invoke(screen,i);
            require(action == actions.invoke(screen,i), "Menu callback not stable: " + i);
            if (i != 7) require(action != actions.invoke(other,i), "Menu callback bound to another frontend");
        }
        int[] exits = new int[2];
        Application oldApp = Gdx.app;
        try {
            Runnable exit = (Runnable)actions.invoke(screen,7);
            for(int i=0;i<2;i++) {
                final int slot=i;
                Gdx.app=(Application)Proxy.newProxyInstance(Application.class.getClassLoader(),new Class<?>[]{Application.class},
                        (p,m,a)->{if(m.getName().equals("exit")) exits[slot]++;return defaultValue(m.getReturnType());});
                exit.run();
            }
            require(Arrays.equals(exits,new int[]{1,1}),"Exit retained a stale application");
        } finally { Gdx.app=oldApp; }
        var props = new java.util.Properties();props.setProperty("lenguaje","es");
        var settings=(GdxGamePresentationSettings)UNSAFE.allocateInstance(GdxGamePresentationSettings.class);
        Field properties=GdxGamePresentationSettings.class.getDeclaredField("properties");properties.setAccessible(true);properties.set(settings,props);
        field("presentationSettings").set(screen,settings);field("initialProperties").set(screen,props);
        var languages=new ArrayList<String>();field("languageChanged").set(screen,(java.util.function.Consumer<String>)languages::add);
        Runnable language=(Runnable)actions.invoke(screen,5);language.run();language.run();
        require(languages.equals(List.of("en","es")),"Bound language action captured stale state");
        // Actual hit() must still honor reveal suppression and retain canonical
        // fresh allocation outside the menu-only scope.
        Method hit=method("hit",float.class,float.class,float.class,float.class,Runnable.class);
        List<Object> hits=(List<Object>)field("hits").get(screen);
        Runnable action=()->{};
        MENU_SCOPE.setBoolean(screen,false);hits.clear();hit.invoke(screen,17f,38f,90f,40f,action);Object original=hits.get(0);
        MENU_SCOPE.setBoolean(screen,true);hits.clear();hit.invoke(screen,17f,38f,90f,40f,action);Object cached=hits.get(0);
        hits.clear();hit.invoke(screen,17f,38f,90f,40f,action);require(cached==hits.get(0),"Menu hit not reused");
        require(original.equals(cached)&&value(cached,"bounds")!=value(original,"bounds"),"Menu hit differs from original owned rectangle");
        require(value(cached,"action")==action && !(Boolean)value(cached,"repeatable"),"Hit callback/ownership changed");
        Method factory=method("mobileMainMenuHit",float.class,float.class,float.class,float.class,Runnable.class);
        for(int key=0;key<5;key++) require(cached!=factory.invoke(screen,key==0?Math.nextUp(17f):17f,
                key==1?Math.nextUp(38f):38f,key==2?Math.nextUp(90f):90f,key==3?Math.nextUp(40f):40f,
                key==4?(Runnable)()->{}:action),"Incomplete hit key "+key);
        Object zero=factory.invoke(screen,0f,0f,90f,40f,action);
        require(zero!=factory.invoke(screen,-0f,0f,90f,40f,action),"Hit signed zero collapsed");
        for(int i=0;i<20;i++) factory.invoke(screen,200f+i,38f,90f,40f,action);
        require(new Rectangle(17f,38f,90f,40f).equals(value(cached,"bounds")),"Eviction mutated pressed/queued hit");
        field("menuRevealStartedAt").setFloat(screen,0f);field("elapsed").setFloat(screen,0f);hits.clear();hit.invoke(screen,17f,38f,90f,40f,action);
        require(hits.isEmpty(),"Cached hit bypassed menu reveal shield");field("menuRevealStartedAt").setFloat(screen,Float.NaN);
        MENU_SCOPE.setBoolean(screen,false);hits.clear();hit.invoke(screen,17f,38f,90f,40f,action);Object outside=hits.get(0);
        hits.clear();hit.invoke(screen,17f,38f,90f,40f,action);require(outside!=hits.get(0),"Nonmenu hits retained");
        Method sound=method("mobileMainMenuSoundBounds");Object bounds=sound.invoke(screen);
        require(bounds==sound.invoke(screen)&&bounds.equals(GdxFrontendScreen.mainMenuSoundBounds()),"Sound bounds differ from static original");
        // Different immutable catalog/settings inputs must re-enter the original
        // label builder, including no mod, mod name and mod version changes.
        var ctor=com.tonikelope.coronapoker.core.media.ModMediaCatalog.class.getDeclaredConstructors()[0];ctor.setAccessible(true);
        Field media=GdxGamePresentationSettings.class.getDeclaredField("modMedia");media.setAccessible(true);
        Method label=method("mobileMainMenuVersionLabel");
        for(String[] input:new String[][]{{"false","",""},{"true","MOD","1"},{"true","MOD","v2"},{"true","OTRO","v2"}}) {
            var next=(GdxGamePresentationSettings)UNSAFE.allocateInstance(GdxGamePresentationSettings.class);
            Object catalog=ctor.newInstance(Path.of("."),Boolean.parseBoolean(input[0]),input[1],input[2],false,false,"",null,"",List.of());
            media.set(next,catalog);field("presentationSettings").set(screen,next);
            Object result=label.invoke(screen);require(result.equals(GdxProductVersionBrand.label(next)),"Version label inputs stale");
            require(result==label.invoke(screen),"Version label not retained");
        }
        Method version=method("mobileMainMenuVersionItem",BitmapFont.class,String.class,float.class,float.class,Color.class,float.class);
        Method fitted=method("fittedTextItem",BitmapFont.class,String.class,float.class,float.class,Color.class,boolean.class,float.class,boolean.class);
        Color color=new Color(.25f,.5f,.75f,.875f);
        Object item=version.invoke(screen,font,"v26.16",16f,20f,color,620f);
        Object reference=fitted.invoke(screen,font,"v26.16",16f,20f,color,false,620f,false);
        require(item.equals(reference)&&Arrays.equals(vertices(screen,List.of(item)),vertices(screen,List.of(reference))),"Version fitting/vertices differ");
        require(item==version.invoke(screen,font,new String("v26.16"),16f,20f,new Color(color),620f),"Version item miss for equal key");
        for(int key=0;key<8;key++) {
            Color tint=new Color(color);if(key>=4){if(key==4)tint.r=Math.nextUp(tint.r);if(key==5)tint.g=Math.nextUp(tint.g);if(key==6)tint.b=Math.nextUp(tint.b);if(key==7)tint.a=Math.nextUp(tint.a);}
            require(item!=version.invoke(screen,font,key==0?"v27":"v26.16",key==1?Math.nextUp(16f):16f,key==2?Math.nextUp(20f):20f,tint,key==3?Math.nextUp(620f):620f),"Incomplete version item key "+key);
            item=version.invoke(screen,font,"v26.16",16f,20f,color,620f);
        }
        float scaleX=font.getData().scaleX,scaleY=font.getData().scaleY;
        font.getData().setScale(scaleX*1.1f,scaleY);require(item!=version.invoke(screen,font,"v26.16",16f,20f,color,620f),"Font scale mutation stale");font.getData().setScale(scaleX,scaleY);
        method("mobileClearMenuTextItems").invoke(screen);
        require(field("mobileMenuVersionItem").get(screen)==null && field("mobileMenuSoundBounds").get(screen)==null
                && Arrays.stream((Object[])field("mobileMenuHits").get(screen)).allMatch(java.util.Objects::isNull),"Lifecycle retained menu hits/version");
        field("presentationSettings").set(screen,null);
        Method volume=method("mobileMasterVolume",String.class);
        var values=new java.util.ArrayList<String>(Arrays.asList(null,""," ","0","-0.0","1","0.8","0.125"," 0.3\t","NaN","Infinity","-Infinity","-1","1.00001","garbage","0x1.0p-1","1e-300","1e300","0.1f",".75","+0.5","0,5"));
        java.util.Random random=new java.util.Random(7290);
        for(int i=0;i<1000;i++) values.add(Float.toString(Float.intBitsToFloat(random.nextInt())));
        for(String raw:values) {
            var originalProperties=new java.util.Properties();if(raw!=null)originalProperties.setProperty("master_volume",raw);
            float expected=GdxSettingsContract.masterVolume(originalProperties),actual=(Float)volume.invoke(screen,raw);
            require(Float.floatToRawIntBits(expected)==Float.floatToRawIntBits(actual),"Master volume oracle mismatch: "+raw);
            require(Float.floatToRawIntBits(actual)==Float.floatToRawIntBits((Float)volume.invoke(screen,raw==null?null:new String(raw))),"Equal volume raw string missed semantic key");
        }
        MethodHandle volumeRace=MethodHandles.lookup().unreflect(volume);
        java.util.concurrent.atomic.AtomicReference<Throwable> raceFailure=new java.util.concurrent.atomic.AtomicReference<>();
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        Thread[] workers=new Thread[2];
        for(int worker=0;worker<workers.length;worker++) {
            final int offset=worker;
            workers[worker]=new Thread(()->{
                try {
                    barrier.await();
                    String[] raw={"0.125","0.875",null,"NaN","-0.0","1.5","0x1.0p-1"};
                    float[] expected={.125f,.875f,.8f,.8f,-0f,.8f,.5f};
                    for(int i=0;i<100000;i++) {
                        int index=(i+offset)%raw.length;
                        float actual=(float)volumeRace.invokeExact(screen,raw[index]);
                        if(Float.floatToRawIntBits(actual)!=Float.floatToRawIntBits(expected[index])) throw new AssertionError("Torn concurrent raw/value snapshot");
                    }
                } catch(Throwable failure) { raceFailure.compareAndSet(null,failure); }
            },"menu-volume-cache-oracle");
            workers[worker].start();
        }
        for(Thread worker:workers)worker.join();
        if(raceFailure.get()!=null)throw new AssertionError(raceFailure.get());
        steadyMenuAllocations(screen,font,color,action);
        System.out.println("PASS menu interaction/version: stable instance callbacks, latest language/app, full hit keys/ownership/eviction/reveal, original sound bounds, immutable mod labels, fitted vertices/RGBA/font-scale/lifecycle");
        System.out.println("PASS master volume: "+values.size()+" raw values vs original static oracle; 2x100000 concurrent raw/value checks; three 100000-hit batches allocate zero host bytes for actions/hits/sound/version/volume");
    }

    private static void steadyMenuAllocations(GdxFrontendScreen screen,BitmapFont font,Color color,Runnable action) throws Exception {
        var lookup=MethodHandles.lookup();
        MethodHandle actionHandle=lookup.unreflect(method("mobileMenuAction",int.class));
        MethodHandle hitHandle=lookup.unreflect(method("mobileMainMenuHit",float.class,float.class,float.class,float.class,Runnable.class)).asType(MethodType.methodType(Object.class,GdxFrontendScreen.class,float.class,float.class,float.class,float.class,Runnable.class));
        MethodHandle soundHandle=lookup.unreflect(method("mobileMainMenuSoundBounds"));
        MethodHandle labelHandle=lookup.unreflect(method("mobileMainMenuVersionLabel"));
        MethodHandle versionHandle=lookup.unreflect(method("mobileMainMenuVersionItem",BitmapFont.class,String.class,float.class,float.class,Color.class,float.class)).asType(MethodType.methodType(Object.class,GdxFrontendScreen.class,BitmapFont.class,String.class,float.class,float.class,Color.class,float.class));
        MethodHandle volumeHandle=lookup.unreflect(method("mobileMasterVolume",String.class));
        var bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().getId();
        for(int round=0;round<5;round++) {
            long before=bean.getThreadAllocatedBytes(thread);
            try {
                for(int i=0;i<100000;i++) {
                    sink=(Runnable)actionHandle.invokeExact(screen,5);
                    sink=(Object)hitHandle.invokeExact(screen,17f,38f,90f,40f,action);
                    sink=(Rectangle)soundHandle.invokeExact(screen);
                    sink=(String)labelHandle.invokeExact(screen);
                    sink=(Object)versionHandle.invokeExact(screen,font,"v26.16",16f,20f,color,620f);
                    floatSink=(float)volumeHandle.invokeExact(screen,"0.8");
                }
            } catch(Throwable failure) { throw new AssertionError(failure); }
            long allocated=bean.getThreadAllocatedBytes(thread)-before;
            if(round>=2)require(allocated==0,"Stable menu helpers allocated "+allocated+" bytes");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> original(GdxFrontendScreen screen, BitmapFont font, String quote,
            float width, int lines) throws Exception {
        return (List<String>) WRAP.invoke(screen, font, quote, width, lines);
    }

    private static void compareLines(GdxFrontendScreen screen, BitmapFont font, String quote,
            float width, int limit) throws Exception {
        List<String> expected = original(screen, font, quote, width, limit);
        require(expected.equals(screen.mobileMenuQuoteLines(font, quote, width, limit)), "Wrapped output changed");
        comparisons++;
    }

    @SuppressWarnings("unchecked")
    private static void compareQuote(GdxFrontendScreen screen, BitmapFont font, String quote,
            boolean enabled) throws Exception {
        List<String> expected = quote.isBlank() ? List.of() : original(screen, font, quote,
                GdxFrontendScreen.MENU_QUOTE_MAX_WIDTH, GdxFrontendScreen.MENU_QUOTE_MAX_LINES);
        List<Object> texts = (List<Object>) TEXTS.get(screen);
        IOSMenuQuoteWrapCache cache = (IOSMenuQuoteWrapCache) CACHE.get(screen);
        // Run the actual draw path first without its cache, then with the flag's
        // cache. Both use the same original TextItem/italic rendering method.
        CACHE.set(screen, null);
        MENU_SCOPE.setBoolean(screen, false);
        texts.clear(); DRAW.invoke(screen, quote);
        int[] originalVertices = vertices(screen, texts);
        CACHE.set(screen, cache);
        List<Object> firstItems = null;
        for (int pass = 0; pass < 2; pass++) {
            MENU_SCOPE.setBoolean(screen, true);
            texts.clear(); DRAW.invoke(screen, quote);
            require(texts.size() == expected.size(), "Quote line count changed");
            float baseline = expected.size() > 1 ? GdxFrontendScreen.MENU_QUOTE_TWO_LINE_TOP_BASELINE
                    : GdxFrontendScreen.MENU_QUOTE_SINGLE_BASELINE;
            for (int i = 0; i < texts.size(); i++) {
                Object item = texts.get(i);
                require(value(item, "font") == font && expected.get(i).equals(value(item, "text")), "Font/text changed");
                require(bits(value(item, "x")) == Float.floatToIntBits(960f)
                        && bits(value(item, "y")) == Float.floatToIntBits(baseline), "Quote origin changed");
                require(Color.WHITE.equals(value(item, "color")) && (Boolean) value(item, "centered")
                        && (Boolean) value(item, "italic") && !(Boolean) value(item, "pixelSnapped")
                        && bits(value(item, "scale")) == Float.floatToIntBits(1f), "Quote style changed");
                baseline -= GdxFrontendScreen.MENU_QUOTE_LINE_HEIGHT;
            }
            require(Arrays.equals(originalVertices, vertices(screen, texts)), "Glyph vertices/color/italic transform changed");
            if (firstItems == null) firstItems = List.copyOf(texts);
            else for (int i = 0; i < texts.size(); i++)
                require(firstItems.get(i) == texts.get(i), "Stable quote rebuilt an immutable item");
            comparisons++;
        }
        MENU_SCOPE.setBoolean(screen, false);
        require(enabled == (CACHE.get(screen) != null), "Control state changed while drawing");
    }

    private static MethodHandle factoryHandle() {
        try {
            return MethodHandles.lookup().unreflect(TEXT_FACTORY).asType(MethodType.methodType(
                    Object.class, GdxFrontendScreen.class, BitmapFont.class, String.class,
                    float.class, float.class, Color.class, boolean.class, boolean.class,
                    float.class, boolean.class));
        } catch (IllegalAccessException failure) { throw new ExceptionInInitializerError(failure); }
    }

    private static Object item(GdxFrontendScreen screen, BitmapFont font, String text,
            float x, float y, Color color, boolean centered, boolean italic,
            float scale, boolean snapped) throws Exception {
        try { return (Object) TEXT_FACTORY_HANDLE.invokeExact(screen, font, text,
                x, y, color, centered, italic, scale, snapped); }
        catch (Exception failure) { throw failure; }
        catch (Throwable failure) { throw new AssertionError(failure); }
    }

    private static Object originalItem(BitmapFont font, String text, float x, float y,
            Color color, boolean centered, boolean italic, float scale, boolean snapped) throws Exception {
        var constructor = TEXT_FACTORY.getReturnType().getDeclaredConstructor(TEXT_FACTORY.getParameterTypes());
        constructor.setAccessible(true);
        return constructor.newInstance(font, text, x, y, new Color(color), centered, italic, scale, snapped);
    }

    private static void immutableTextItems(GdxFrontendScreen screen, BitmapFont font,
            GdxAdaptiveFontQuality.Profile profile, FreeTypeFontGenerator generator) throws Exception {
        method("mobileClearMenuTextItems").invoke(screen);
        String label = "Árbol — MENU";
        Color color = new Color(.25f, .5f, .75f, .875f);
        MENU_SCOPE.setBoolean(screen, false);
        Object outside = item(screen, font, label, 17.25f, 38.5f, color, false, false, 1f, false);
        require(outside != item(screen, font, label, 17.25f, 38.5f, color, false, false, 1f, false),
                "Non-menu construction unexpectedly retained items");
        MENU_SCOPE.setBoolean(screen, true);
        Object first = item(screen, font, label, 17.25f, 38.5f, color, false, false, 1f, false);
        require(first == item(screen, font, new String(label), 17.25f, 38.5f, new Color(color), false, false, 1f, false),
                "Equal text/RGBA content failed to reuse item");
        require(value(first, "color") != color, "Item retained caller's mutable colour");
        color.r = Math.nextUp(color.r);
        require(Float.floatToRawIntBits(((Color)value(first,"color")).r) == Float.floatToRawIntBits(.25f),
                "Caller colour mutation changed queued item");
        require(first != item(screen, font, label, 17.25f, 38.5f, color, false, false, 1f, false), "Red bits missing from key");
        color.r = .25f;
        for (int key = 0; key < 10; key++) {
            Color tint = new Color(color);
            float x = 17.25f, y = 38.5f, scale = 1f;
            String text = label;
            boolean centered = false, italic = false, snapped = false;
            switch (key) {
                case 0 -> text += " changed";
                case 1 -> x = Math.nextUp(x);
                case 2 -> y = Math.nextUp(y);
                case 3 -> scale = Math.nextUp(scale);
                case 4 -> tint.g = Math.nextUp(tint.g);
                case 5 -> tint.b = Math.nextUp(tint.b);
                case 6 -> tint.a = Math.nextUp(tint.a);
                case 7 -> centered = true;
                case 8 -> italic = true;
                case 9 -> snapped = true;
            }
            require(first != item(screen,font,text,x,y,tint,centered,italic,scale,snapped), "Incomplete final item key " + key);
        }
        BitmapFont recreated = GdxAdaptiveFontQuality.generate(generator, 18, 0f, Color.WHITE,
                new Color(0x02050ccc), GdxFrontendScreen.FRONTEND_EXTRA_FONT_CHARACTERS, profile);
        try { require(first != item(screen,recreated,label,17.25f,38.5f,color,false,false,1f,false), "Font identity missing from key"); }
        finally { recreated.dispose(); }
        Object zero = item(screen,font,label,0f,0f,color,false,false,1f,false);
        require(zero != item(screen,font,label,-0f,0f,color,false,false,1f,false), "Signed zero bits collapsed");
        Object nan = item(screen,font,label,Float.intBitsToFloat(0x7fc00001),0f,color,false,false,1f,false);
        require(nan != item(screen,font,label,Float.intBitsToFloat(0x7fc00002),0f,color,false,false,1f,false), "NaN payload bits collapsed");

        var viewport = (FitViewport) field("viewport").get(screen);
        for (int extent : new int[]{1366, 1920}) {
            viewport.setScreenBounds(3,5,extent,768);
            for (String text : new String[]{"MENU", "Árbol, corazón y acción", "Long menu label ".repeat(6)})
                for (float scale : new float[]{.7f,1f,1.15f}) for (int flags = 0; flags < 8; flags++) {
                    Object original = originalItem(font,text,17.25f,38.5f,color,(flags&1)!=0,(flags&2)!=0,scale,(flags&4)!=0);
                    Object cached = item(screen,font,text,17.25f,38.5f,color,(flags&1)!=0,(flags&2)!=0,scale,(flags&4)!=0);
                    require(original.equals(cached),"Final record fields differ from original constructor");
                    require(Arrays.equals(vertices(screen,List.of(original)),vertices(screen,List.of(cached))),"Retained-item glyph vertices changed");
                    textItemComparisons++;
                }
        }
        var texts = (List<?>)TEXTS.get(screen);
        for (String language : new String[]{"es","en","es"}) {
            ((GdxGameText)GAME_TEXT.get(screen)).setLanguage(language);
            String translated = ((GdxGameText)GAME_TEXT.get(screen)).translate("menu.ajustes");
            for (float width : new float[]{400f,91.25f}) {
                var fitted = method("fittedTextItem",BitmapFont.class,String.class,float.class,float.class,Color.class,boolean.class,float.class,boolean.class);
                MENU_SCOPE.setBoolean(screen,false);
                Object original = fitted.invoke(screen,font,translated,17.25f,38.5f,color,true,width,true);
                MENU_SCOPE.setBoolean(screen,true);
                Object cached = fitted.invoke(screen,font,translated,17.25f,38.5f,color,true,width,true);
                require(original.equals(cached),"Actual font fitting changed");
                require(Arrays.equals(vertices(screen,List.of(original)),vertices(screen,List.of(cached))),"Fitted glyph vertices changed");
                var pixel = method("pixelSnappedTextInBox",BitmapFont.class,String.class,float.class,float.class,float.class,float.class,Color.class);
                MENU_SCOPE.setBoolean(screen,false);texts.clear();pixel.invoke(screen,font,translated,17.25f,38.5f,width,40f,color);
                original=texts.get(0);
                MENU_SCOPE.setBoolean(screen,true);texts.clear();pixel.invoke(screen,font,translated,17.25f,38.5f,width,40f,color);
                cached=texts.get(0);
                require(original.equals(cached),"Actual pixel-snapped positioning changed");
                require(Arrays.equals(vertices(screen,List.of(original)),vertices(screen,List.of(cached))),"Pixel-snapped glyph vertices changed");
                textItemComparisons += 2;
            }
        }
        method("mobileClearMenuTextItems").invoke(screen);
        first = item(screen,font,label,17.25f,38.5f,color,false,false,1f,false);
        Color retained = (Color)value(first,"color");
        int capacity = Array.getLength(TEXT_CACHE.get(screen));
        require(capacity == 64,"Menu cache must stay bounded");
        for (int i=0;i<capacity;i++) item(screen,font,"replacement "+i,17.25f,38.5f,color,false,false,1f,false);
        require(first != item(screen,font,label,17.25f,38.5f,color,false,false,1f,false),"Ring did not evict old slot");
        require(label.equals(value(first,"text")) && retained == value(first,"color") && retained.equals(color),"Eviction mutated an item still queued for draw");
        for (int i=0;i<50000;i++) sink=item(screen,font,label,17.25f,38.5f,color,false,false,1f,false);
        var bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().getId();
        itemAllocations(bean,thread,screen,font,label,color);itemAllocations(bean,thread,screen,font,label,color);
        for(int round=0;round<3;round++) require(itemAllocations(bean,thread,screen,font,label,color)==0,"Stable factory hit allocated");
        MENU_SCOPE.setBoolean(screen,false);
    }

    private static long itemAllocations(ThreadMXBean bean,long thread,GdxFrontendScreen screen,
            BitmapFont font,String text,Color color) throws Exception {
        long before=bean.getThreadAllocatedBytes(thread);
        for(int i=0;i<100000;i++) sink=item(screen,font,text,17.25f,38.5f,color,false,false,1f,false);
        return bean.getThreadAllocatedBytes(thread)-before;
    }

    private static int[] vertices(GdxFrontendScreen screen, List<Object> texts) throws Exception {
        CaptureBatch batch = (CaptureBatch) field("batch").get(screen);
        batch.values.clear();
        for (Object item : texts) method("drawTextItem", item.getClass()).invoke(screen, item);
        return batch.values.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void invalidationAndHits(GdxFrontendScreen screen, BitmapFont font,
            GdxAdaptiveFontQuality.Profile profile, FreeTypeFontGenerator generator) throws Exception {
        IOSMenuQuoteWrapCache cache = (IOSMenuQuoteWrapCache) CACHE.get(screen);
        GdxGameText text = (GdxGameText) GAME_TEXT.get(screen);
        text.setLanguage("en");
        PROFILE.set(screen, profile);
        String quote = "A stable poker quotation has no reason to wrap every frame.";
        cache.clear();
        List<String> first = screen.mobileMenuQuoteLines(font, quote, 360f, 2);
        GlyphLayout glyph = (GlyphLayout) GLYPH.get(screen);
        GLYPH.set(screen, null);
        require(screen.mobileMenuQuoteLines(font, new String(quote), 360f, 2) == first,
                "Equal quote content should hit without the original glyph layout");
        PROFILE.set(screen, new GdxAdaptiveFontQuality.Profile(profile.physicalPixelsPerWorldUnit()));
        require(screen.mobileMenuQuoteLines(font, quote, 360f, 2) == first, "Equal profile values should hit");
        for (int i = 0; i < 50000; i++) sink = screen.mobileMenuQuoteLines(font, quote, 360f, 2);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        // Warm the measuring call site as well as the cache. Require three
        // consecutive zero-allocation batches after the measurement warmup;
        // a per-hit allocation cannot disappear in these measured batches.
        hitAllocations(bean, thread, screen, font, quote);
        hitAllocations(bean, thread, screen, font, quote);
        for (int batch = 0; batch < 3; batch++) {
            long allocated = hitAllocations(bean, thread, screen, font, quote);
            require(allocated == 0, "Steady cache hits allocated " + allocated + " bytes");
        }
        GLYPH.set(screen, glyph);

        text.setLanguage("es"); require(cache.get(font, profile, "es", quote, 360f, 2) == null, "Language failed to invalidate");
        compareLines(screen, font, quote, 360f, 2); text.setLanguage("en");
        compareLines(screen, font, quote + " Changed.", 360f, 2);
        compareLines(screen, font, quote, Math.nextUp(360f), 2);
        compareLines(screen, font, quote, 360f, 1);
        cache.put(font, profile, "en", quote, 360f, 2, first);
        float sx = font.getData().scaleX, sy = font.getData().scaleY;
        font.getData().setScale(sx * 1.25f, sy);
        require(cache.get(font, profile, "en", quote, 360f, 2) == null, "ScaleX failed to invalidate");
        compareLines(screen, font, quote, 360f, 2);
        font.getData().setScale(sx, sy * 1.25f);
        require(cache.get(font, profile, "en", quote, 360f, 2) == null, "ScaleY failed to invalidate");
        compareLines(screen, font, quote, 360f, 2);
        font.getData().setScale(sx, sy);
        cache.put(font, profile, "en", quote, 360f, 2, first);
        var changedProfile = new GdxAdaptiveFontQuality.Profile(profile.physicalPixelsPerWorldUnit() + .125f);
        require(cache.get(font, changedProfile, "en", quote, 360f, 2) == null, "Profile failed to invalidate");
        PROFILE.set(screen, changedProfile); compareLines(screen, font, quote, 360f, 2); PROFILE.set(screen, profile);

        BitmapFont recreated = GdxAdaptiveFontQuality.generate(generator, 18, 0f, Color.WHITE,
                new Color(0x02050ccc), GdxFrontendScreen.FRONTEND_EXTRA_FONT_CHARACTERS, profile);
        try {
            cache.put(font, profile, "en", quote, 360f, 2, first);
            require(cache.get(recreated, profile, "en", quote, 360f, 2) == null, "Recreated font failed to invalidate");
            compareLines(screen, recreated, quote, 360f, 2);
            // BitmapFont normally retains its data for life. Independently
            // exercise the data identity guard without changing any metrics.
            Field dataField = BitmapFont.class.getDeclaredField("data"); dataField.setAccessible(true);
            var data = font.getData();
            cache.put(font, profile, "en", quote, 360f, 2, first);
            dataField.set(font, recreated.getData());
            try { require(cache.get(font, profile, "en", quote, 360f, 2) == null, "Data identity failed to invalidate"); }
            finally { dataField.set(font, data); }
        } finally { recreated.dispose(); }
        // Each key must be able to force a real wrapper call, not just miss a
        // helper lookup. With glyph removed a stale hit would wrongly succeed.
        for (int key = 0; key < 5; key++) {
            PROFILE.set(screen, profile); text.setLanguage("en");
            cache.put(font, profile, "en", quote, 360f, 2, first); GLYPH.set(screen, null);
            final int selected = key;
            if (key == 3) text.setLanguage("es");
            if (key == 4) PROFILE.set(screen, changedProfile);
            expectFailure(() -> screen.mobileMenuQuoteLines(font,
                    selected == 0 ? quote + " Changed" : quote,
                    selected == 1 ? Math.nextUp(360f) : 360f, selected == 2 ? 1 : 2));
            GLYPH.set(screen, glyph);
        }
        PROFILE.set(screen, profile); text.setLanguage("en"); cache.clear();
    }

    private static long hitAllocations(ThreadMXBean bean, long thread,
            GdxFrontendScreen screen, BitmapFont font, String quote) {
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 100000; i++) sink = screen.mobileMenuQuoteLines(font, quote, 360f, 2);
        return bean.getThreadAllocatedBytes(thread) - before;
    }

    private static void rotationAndLifecycle(GdxFrontendScreen screen, BitmapFont font,
            GdxAdaptiveFontQuality.Profile profile) throws Exception {
        var quotes = new GdxMenuQuotes(new SecureRandom(), List.of("primera", "segunda"), List.of("first", "second"));
        String first = quotes.update("en", 0f);
        List<String> wrapped = screen.mobileMenuQuoteLines(font, first, 360f, 2);
        require(first.equals(quotes.update("en", 7.99f)), "Rotation changed before 8s");
        require(screen.mobileMenuQuoteLines(font, first, 360f, 2) == wrapped, "Stable rotation quote missed");
        String next = quotes.update("en", .02f);
        require(!first.equals(next), "Quote failed to rotate after 8s"); compareLines(screen, font, next, 360f, 2);
        // Stop these real lifecycle methods immediately after the inserted
        // clear: no fake audio/platform lifecycle is executed by this probe.
        var cache = (IOSMenuQuoteWrapCache) CACHE.get(screen);
        cache.put(font, profile, "en", next, 360f, 2, wrapped);
        Gdx.files = null;
        expectFailure(() -> method("rebuildFonts", GdxAdaptiveFontQuality.Profile.class).invoke(screen, profile));
        require(cache.get(font, profile, "en", next, 360f, 2) == null, "Font rebuild retained cache");
        require(Arrays.stream((Object[])TEXT_CACHE.get(screen)).allMatch(java.util.Objects::isNull), "Font rebuild retained text items");
        cache.put(font, profile, "en", next, 360f, 2, wrapped);
        MENU_SCOPE.setBoolean(screen,true);
        item(screen,font,"dispose clear",0f,0f,Color.WHITE,false,false,1f,false);
        MENU_SCOPE.setBoolean(screen,false);
        expectFailure(() -> method("dispose").invoke(screen));
        require(cache.get(font, profile, "en", next, 360f, 2) == null, "Dispose retained cache");
        require(Arrays.stream((Object[])TEXT_CACHE.get(screen)).allMatch(java.util.Objects::isNull), "Dispose retained text items");
    }

    public static final class CaptureBatch extends SpriteBatch {
        ArrayList<Integer> values;
        @Override public void setTransformMatrix(Matrix4 matrix) {
            for (float value : matrix.val) values.add(Float.floatToIntBits(value));
        }
        @Override public void draw(Texture texture, float[] vertices, int offset, int count) {
            for (int i = offset; i < offset + count; i++) values.add(Float.floatToIntBits(vertices[i]));
        }
    }
    private interface Checked { void run() throws Exception; }
    private static void expectFailure(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected original wrapper/lifecycle body to be entered"); }
        catch (NullPointerException expected) { }
        catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof NullPointerException, "Unexpected reflected failure: " + expected.getCause());
        }
    }
    private static Object value(Object item, String name) throws Exception {
        Method m = item.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(item);
    }
    private static int bits(Object value) { return Float.floatToIntBits((Float) value); }
    private static Field field(String name) {
        try { Field f = GdxFrontendScreen.class.getDeclaredField(name); f.setAccessible(true); return f; }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static Method method(String name, Class<?>... types) {
        try { Method m = GdxFrontendScreen.class.getDeclaredMethod(name, types); m.setAccessible(true); return m; }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static Unsafe unsafe() {
        try { Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); return (Unsafe) f.get(null); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false; if (type == int.class) return 0;
        if (type == float.class) return 0f; if (type == long.class) return 0L;
        if (type == double.class) return 0d; return null;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
