package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.tonikelope.coronapoker.ios.IOSVideo;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** UIKit owns the video surface and input. The shared overlay owns the catalogue and controls. */
final class PokerVideoRuntime implements AutoCloseable {
    private static final AtomicLong OWNERS = new AtomicLong();
    private final long owner = OWNERS.incrementAndGet();
    private List<PokerVideoSearch.Video> videos;
    private int index;
    private boolean active = true;
    private boolean closed;
    private static volatile PokerVideoRuntime presented;
    private String lastID;
    static void background() {
        PokerVideoRuntime player = presented;
        if (player != null) player.hide();
    }
    private float lastX, lastY, lastWidth, lastHeight, lastOpacity;

    PokerVideoRuntime(List<PokerVideoSearch.Video> videos) { this.videos = List.copyOf(videos); }
    record Selection(PokerVideoSearch.Video video, int index, int total) {}
    synchronized Selection selection() { return new Selection(videos.get(index), index, videos.size()); }
    synchronized void replaceVideos(List<PokerVideoSearch.Video> list, String language, boolean ordered) {
        if (list.isEmpty()) return;
        videos = List.copyOf(list);
        index = ordered ? Math.min(index, list.size() - 1) : 0;
    }
    synchronized void extendDiscovery(List<PokerVideoSearch.Video> list) {
        if (!list.isEmpty()) {
            String id = videos.get(index).id();
            videos = List.copyOf(list);
            index = 0;
            for (int i = 0; i < videos.size(); i++) if (videos.get(i).id().equals(id)) { index = i; break; }
        }
    }
    synchronized boolean discoveryRequested() { return index == videos.size() - 1; }
    synchronized void selectRelative(int delta) { index = Math.floorMod(index + delta, videos.size()); }
    synchronized boolean ready() { return active && !closed; }
    synchronized void setActive(boolean value) { active = value; if (!value) hide(); }
    synchronized void hide() {
        if (lastID != null) IOSVideo.hide(owner);
        lastID = null;
        if (presented == this) presented = null;
    }
    synchronized void present(Viewport vp, Rectangle bounds, float opacity) {
        if (!ready()) { hide(); return; }
        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        float sx = vp.getScreenWidth() / vp.getWorldWidth(), sy = vp.getScreenHeight() / vp.getWorldHeight();
        float x = (vp.getScreenX()+bounds.x*sx)/w, y = 1f-(vp.getScreenY()+(bounds.y+bounds.height)*sy)/h;
        float width = bounds.width*sx/w, height = bounds.height*sy/h;
        String id = videos.get(index).id();
        if (id.equals(lastID) && x==lastX && y==lastY && width==lastWidth && height==lastHeight && opacity==lastOpacity) return;
        presented = this;
        IOSVideo.show(owner,id,x,y,width,height,opacity);
        lastID=id; lastX=x; lastY=y; lastWidth=width; lastHeight=height; lastOpacity=opacity;
    }
    @Override public synchronized void close() { closed=true; hide(); }
}
