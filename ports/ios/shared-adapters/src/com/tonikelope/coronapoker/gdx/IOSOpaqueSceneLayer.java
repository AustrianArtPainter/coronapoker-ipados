package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;

/** One retained opaque closing surface; all modal composition stays with the table. */
final class IOSOpaqueSceneLayer {
    private FrameBuffer buffer;
    private int width, height;
    private boolean valid;

    void prepare(int nextWidth, int nextHeight) {
        if (buffer != null && width == nextWidth && height == nextHeight) return;
        dispose();
        buffer = new FrameBuffer(Pixmap.Format.RGBA8888, nextWidth, nextHeight, false);
        buffer.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        width = nextWidth; height = nextHeight;
    }
    boolean valid() { return valid; }
    void invalidate() { valid = false; }
    void begin() {
        if (buffer == null) throw new IllegalStateException("Scene surface was not prepared");
        valid = false;
        buffer.begin();
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT);
    }
    void end() { buffer.end(); valid = true; }
    Texture texture() { return buffer.getColorBufferTexture(); }
    static void replay(SpriteBatch batch, Texture texture, float worldWidth, float worldHeight) {
        // This overload assigns (u, v) to the bottom-left vertex. An FBO's
        // bottom row is v=0; ordinary decoded image textures use the reverse.
        batch.draw(texture, 0f, 0f, worldWidth, worldHeight, 0f, 0f, 1f, 1f);
    }
    void dispose() {
        if (buffer != null) buffer.dispose();
        buffer = null; valid = false;
    }
}
