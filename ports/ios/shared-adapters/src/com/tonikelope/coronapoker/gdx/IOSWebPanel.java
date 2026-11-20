package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.viewport.Viewport;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** UIKit handles scrolling, text selection, typing and touches inside the web rectangle. */
abstract class IOSWebPanel implements AutoCloseable {
    private static final AtomicLong NEXT = new AtomicLong();
    private static final CopyOnWriteArrayList<IOSWebPanel> panels = new CopyOnWriteArrayList<>();
    final long owner = NEXT.incrementAndGet();
    private IOSWebOrigin origin;
    private String entry;
    volatile boolean closed, failed, ready;
    private boolean shown, drawn;
    private float lastX, lastY, lastWidth, lastHeight, lastAlpha;
    synchronized void attach(IOSWebOrigin server, String fragment) {
        if (closed) { server.close(); return; }
        origin = server;
        entry = origin.entry(fragment);
        panels.add(this);
    }
    synchronized boolean present(Viewport vp, Rectangle bounds, float opacity) {
        if (closed || failed || entry == null) return false;
        float sx = vp.getScreenWidth() / (vp.getWorldWidth() * Gdx.graphics.getWidth());
        float sy = vp.getScreenHeight() / (vp.getWorldHeight() * Gdx.graphics.getHeight());
        float x = (float)vp.getScreenX()/Gdx.graphics.getWidth() + bounds.x*sx;
        float y = 1f - (float)vp.getScreenY()/Gdx.graphics.getHeight() - (bounds.y+bounds.height)*sy;
        float w=bounds.width*sx, h=bounds.height*sy;
        drawn = true;
        if (!shown || x!=lastX || y!=lastY || w!=lastWidth || h!=lastHeight || opacity!=lastAlpha) {
            display(owner,entry,x,y,w,h,opacity);
            lastX=x; lastY=y; lastWidth=w; lastHeight=h; lastAlpha=opacity; shown=true;
        }
        return true;
    }
    private static int frameDepth;
    static void beginFrame() { frameDepth++; }
    static void endFrame() {
        if (--frameDepth != 0) return;
        for (int i=panels.size()-1; i>=0; i--) {
            // Lifecycle changes run on the render thread except for first attachment.
            IOSWebPanel panel = panels.get(i);
            if (panel.shown && !panel.drawn) { visible(panel.owner,false); panel.shown=false; }
            panel.drawn=false;
        }
    }
    public synchronized void close() {
        closed=true;
        panels.remove(this);
        if (origin != null) origin.close();
        release(owner);
    }
    boolean interactive() { return !closed && !failed && ready; }
    boolean failed() { return failed || (shown && loadFailed(owner)); }
    void unfocus() { if (shown) evaluate(owner,"document.activeElement?.blur?.()"); }
    // UIKit dispatches hardware keys and pointer events directly to the WKWebView.
    // GDX only sees gestures outside it, including the surrounding window chrome.
    boolean pressed() { return false; }
    com.badlogic.gdx.graphics.Cursor.SystemCursor cursor() { return com.badlogic.gdx.graphics.Cursor.SystemCursor.Arrow; }
    void mouse(int event,float x,float y) {}
    void mouse(int event,int x,int y,int button) {}
    void key(int event,int key,char character) {}
    void wheel(float x,float y,float amount) {}
    void updateKeyboard(float delta) {}
    void updateKeyboard(float delta,boolean enabled) {}

    static native void display(long owner,String url,float x,float y,float w,float h,float opacity);
    static native void visible(long owner,boolean show);
    static native void release(long owner);
    static native boolean loadFailed(long owner);
    static native void evaluate(long owner,String script);
}
