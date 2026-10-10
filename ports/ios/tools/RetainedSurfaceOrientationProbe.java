package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import sun.misc.Unsafe;

/** Inspects actual libGDX vertices; GL framebuffer row zero is the bottom row.
 * Unsafe is confined to this host probe, never packaged in the iPad app.
 */
public final class RetainedSurfaceOrientationProbe {
    private static final Unsafe MEMORY;
    private static int checks;
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true); MEMORY = (Unsafe) field.get(null);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void set(Object owner, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true); field.set(owner, value);
    }
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Texture texture = (Texture) MEMORY.allocateInstance(Texture.class);
        TextureData data = (TextureData) Proxy.newProxyInstance(TextureData.class.getClassLoader(),
                new Class<?>[]{TextureData.class}, (proxy, method, values) -> switch (method.getName()) {
                    case "getWidth" -> 2420;
                    case "getHeight" -> 1668;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        set(texture, Texture.class, "data", data);
        SpriteBatch batch = (SpriteBatch) MEMORY.allocateInstance(SpriteBatch.class);
        float[] vertices = new float[40];
        set(batch, SpriteBatch.class, "vertices", vertices);
        set(batch, SpriteBatch.class, "drawing", true);
        set(batch, SpriteBatch.class, "lastTexture", texture);
        set(batch, SpriteBatch.class, "invTexWidth", 1f / 2420);
        set(batch, SpriteBatch.class, "invTexHeight", 1f / 1668);
        for (float width : new float[]{1668, 1920, 2420}) {
            set(batch, SpriteBatch.class, "idx", 0);
            IOSOpaqueSceneLayer.replay(batch, texture, width, 1668);
            for (int corner = 0; corner < 4; corner++) {
                int offset = corner * 5;
                float x = vertices[offset], y = vertices[offset + 1];
                float u = vertices[offset + 3], v = vertices[offset + 4];
                check(u == x / width, "Retained framebuffer must not swap left/right corners");
                check(v == y / 1668, "Retained framebuffer must not swap top/bottom corners");
            }
        }
        set(batch, SpriteBatch.class, "idx", 0);
        batch.draw(texture, 0, 0, 2420, 1668);
        check(vertices[4] == 1f && vertices[9] == 0f,
                "Ordinary image texture conventions must remain unchanged");
        System.out.println("PASS " + checks + " actual libGDX retained-framebuffer corner/orientation checks");
    }
}
