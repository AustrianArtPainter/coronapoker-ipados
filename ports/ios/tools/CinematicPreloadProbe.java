package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.tonikelope.coronapoker.ios.PortableGif;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import sun.misc.Unsafe;

/** Actual nine-clip timeline, no playback allocation and borrowed-owner contract. */
public final class CinematicPreloadProbe {
    private static volatile Object sink;
    public static void main(String[] args) throws Exception {
        var unsafeField = Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe)unsafeField.get(null);
        var framesField = PreloadedShuffleAnimation.class.getDeclaredField("frames"); framesField.setAccessible(true);
        var uploadedField = PreloadedShuffleAnimation.class.getDeclaredField("uploaded"); uploadedField.setAccessible(true);
        var pendingField = PreloadedShuffleAnimation.class.getDeclaredField("pending"); pendingField.setAccessible(true);
        ThreadMXBean bean = (ThreadMXBean)ManagementFactory.getThreadMXBean(); bean.setThreadAllocatedMemoryEnabled(true);
        long bytes = 0; int frameCount = 0;
        for (String name : IOSCinematicAssets.OFFICIAL) {
            byte[] data = Files.readAllBytes(Path.of(args[0], "cinematics/allin", name));
            PortableGif.Info info = PortableGif.inspect(data);
            var pixels = PreloadedShuffleAnimation.prepare(data, 563);
            require(pixels.pixels.length == info.frames().size());
            require(pixels.ends[pixels.ends.length-1] == info.durationMillis());
            for (byte[] rgba : pixels.pixels) { require(rgba.length == pixels.width*pixels.height*4); bytes += rgba.length; }
            var cached = new PreloadedShuffleAnimation(pixels);
            TextureRegion[] frames = (TextureRegion[])framesField.get(cached);
            // No GL context in this probe. Only test already-uploaded reference
            // selection; actual preparation and GPU lifetime are device checks.
            for (int i=0;i<frames.length;i++) {
                frames[i] = new TextureRegion(); frames[i].setTexture((Texture)unsafe.allocateInstance(Texture.class));
            }
            uploadedField.setInt(cached, frames.length); pendingField.set(cached, null);
            StreamingGifTextureAnimation borrowed = StreamingGifTextureAnimation.borrowed(cached);
            long start = 0;
            for(int i=0;i<frames.length;i++) {
                require(borrowed.frameAt((start+1)/1000f) == frames[i].getTexture()); start = pixels.ends[i];
            }
            require(borrowed.frameAt(-1) == frames[0].getTexture());
            require(borrowed.frameAt(cached.durationSeconds()+1) == frames[frames.length-1].getTexture());
            require(!borrowed.playbackComplete(cached.durationSeconds()-0.01f));
            require(borrowed.playbackComplete(cached.durationSeconds()));
            for(int i=0;i<20000;i++)sink=borrowed.frameAt(i/1000f);
            long thread=Thread.currentThread().getId(),before=bean.getThreadAllocatedBytes(thread);
            for(int i=0;i<100000;i++)sink=borrowed.frameAt(i/1000f);
            long allocated=bean.getThreadAllocatedBytes(thread)-before;
            require(allocated==0);
            borrowed.dispose(); borrowed.dispose();
            require(borrowed.frameAt(0)==null && cached.ready());
            require(cached.frameAt(0,false) == frames[0]);
            frameCount+=frames.length;
            System.out.println("PASS "+name+": "+frames.length+" exact frames, duration "+info.durationMillis()+"ms, playback allocations="+allocated+", borrowed disposal keeps table assets");
        }
        System.out.println("PASS nine clips, "+frameCount+" preserved frames, "+bytes+" prepared RGBA bytes");
    }
    private static void require(boolean value) { if(!value)throw new AssertionError("Cinematic lifecycle/timeline changed"); }
}
