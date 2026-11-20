package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.viewport.Viewport;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/** Converts logical clipping bounds into the pixels of the current render target. */
final class GdxMobileScissor {
    // Rendering and GL calls belong to the single libGDX render thread.
    private static final IntBuffer GL_VIEWPORT = ByteBuffer.allocateDirect(16)
            .order(ByteOrder.nativeOrder()).asIntBuffer();

    static void set(Viewport viewport, int x, int y, int width, int height) {
        if (viewport.getScreenWidth() <= 0 || viewport.getScreenHeight() <= 0) return;
        GL_VIEWPORT.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, GL_VIEWPORT);
        float scaleX = (float) GL_VIEWPORT.get(2) / viewport.getScreenWidth();
        float scaleY = (float) GL_VIEWPORT.get(3) / viewport.getScreenHeight();
        Gdx.gl.glScissor(
                GL_VIEWPORT.get(0) + Math.round((x - viewport.getScreenX()) * scaleX),
                GL_VIEWPORT.get(1) + Math.round((y - viewport.getScreenY()) * scaleY),
                Math.max(1, Math.round(width * scaleX)),
                Math.max(1, Math.round(height * scaleY)));
    }

    private GdxMobileScissor() {}
}
