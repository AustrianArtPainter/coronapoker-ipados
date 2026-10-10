package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** Checks full decode, exact timing and allocation-free cached frame lookup. */
public final class ShufflePreloadProbe {
    private static volatile Object sink;
    public static void main(String[] args) throws Exception {
        for (String deck : new String[]{"goliat", "goliat4", "coronapoker", "interstate60"}) {
            byte[] data = Files.readAllBytes(Path.of(args[0], "images/decks", deck, "gif/shuffle.gif"));
            var prepared = PreloadedShuffleAnimation.prepare(data, 960);
            require(prepared.pixels.length == 86 && prepared.width == 960 && prepared.height == 540);
            long previous = 0;
            for (int i = 0; i < 86; i++) {
                require(prepared.ends[i] > previous);
                require(prepared.pixels[i].length == 960 * 540 * 4);
                previous = prepared.ends[i];
            }
            var animation = new PreloadedShuffleAnimation(prepared);
            // Frame selection is pure and does not require a GL context. The
            // real GL upload/lifetime is additionally checked on the device.
            var field = PreloadedShuffleAnimation.class.getDeclaredField("frames");
            field.setAccessible(true);
            TextureRegion[] frames = (TextureRegion[]) field.get(animation);
            for (int i = 0; i < frames.length; i++) frames[i] = new TextureRegion();
            var count = PreloadedShuffleAnimation.class.getDeclaredField("uploaded");
            count.setAccessible(true); count.setInt(animation, frames.length);
            long start = 0;
            for (int i = 0; i < frames.length; i++) {
                require(animation.frameAt((start + 1) / 1000f) == frames[i]);
                start = prepared.ends[i];
            }
            require(animation.frameAt(animation.durationSeconds()) == frames[0]);
            require(animation.frameAt(-1) == frames[0]);
            require(animation.frameStartSeconds(1) == 0);
            require(animation.frameStartSeconds(31) == prepared.ends[29] / 1000f);
            for (int i = 0; i < 20000; i++) sink = animation.frameAt(i / 1000f);
            ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            bean.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().getId();
            long before = bean.getThreadAllocatedBytes(thread);
            for (int i = 0; i < 100000; i++) sink = animation.frameAt(i / 1000f);
            long bytes = bean.getThreadAllocatedBytes(thread) - before;
            require(bytes == 0);
            System.out.println("PASS " + deck + ": 86 predecoded frames, exact loop/audio timeline, playback allocations=" + bytes);
        }
    }
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Shuffle preload/timing invariant failed");
    }
}
