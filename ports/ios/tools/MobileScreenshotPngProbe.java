package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.GdxNativesLoader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import sun.misc.Unsafe;

/** Calls the actual screenshot readback/writer using a two-row fake GL frame. */
public final class MobileScreenshotPngProbe {
    public static void main(String[] args) throws Exception {
        GdxNativesLoader.load();
        System.setProperty("user.home", Files.createTempDirectory("cp-png-probe-").toString());
        Gdx.gl = (GL20) Proxy.newProxyInstance(GL20.class.getClassLoader(), new Class<?>[]{GL20.class},
                (proxy, method, values) -> {
                    if (method.getName().equals("glReadPixels")) {
                        ByteBuffer pixels = (ByteBuffer) values[6];
                        // GL supplies bottom row first: red below, green above.
                        pixels.put(new byte[]{-1,0,0,-1, -1,0,0,-1, 0,-1,0,-1, 0,-1,0,-1});
                        pixels.position(0);
                    }
                    return method.getReturnType() == int.class ? 0
                            : method.getReturnType() == boolean.class ? false : null;
                });
        Gdx.graphics = (Graphics) Proxy.newProxyInstance(Graphics.class.getClassLoader(), new Class<?>[]{Graphics.class},
                (proxy, method, values) -> method.getReturnType() == int.class ? 2 : null);
        CountDownLatch finished = new CountDownLatch(1);
        Gdx.app = (Application) Proxy.newProxyInstance(Application.class.getClassLoader(), new Class<?>[]{Application.class},
                (proxy, method, values) -> {
                    if (method.getName().equals("postRunnable")) {
                        try { ((Runnable) values[0]).run(); } finally { finished.countDown(); }
                    }
                    return null;
                });
        Field memoryField = Unsafe.class.getDeclaredField("theUnsafe"); memoryField.setAccessible(true);
        Unsafe memory = (Unsafe) memoryField.get(null);
        CoronaPokerGdxTable table = (CoronaPokerGdxTable) memory.allocateInstance(CoronaPokerGdxTable.class);
        Field text = CoronaPokerGdxTable.class.getDeclaredField("gameText"); text.setAccessible(true); text.set(table, new GdxGameText("es"));
        Method capture = CoronaPokerGdxTable.class.getDeclaredMethod("captureScreenshot"); capture.setAccessible(true); capture.invoke(table);
        if (!finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("Actual screenshot writer did not finish");
        var shots = GdxScreenshotStore.scan();
        if (shots.size() != 1) throw new AssertionError("Actual screenshot writer did not create a managed PNG");
        var image = ImageIO.read(shots.get(0).file().toFile());
        if (image == null || image.getWidth() != 2 || image.getHeight() != 2) throw new AssertionError("PNG dimensions/decode failed");
        if ((image.getRGB(0,0) & 0xffffff) != 0x00ff00 || (image.getRGB(0,1) & 0xffffff) != 0xff0000)
            throw new AssertionError("Screenshot PNG is vertically inverted by a second Y flip");
        System.out.println("PASS: actual render readback and asynchronous PNG writer preserve dimensions and top/bottom pixels");
    }
}
