package com.tonikelope.coronapoker.gdx;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises route loss/recovery and the render-thread scheduling boundary. */
public final class AudioTopologyProbe {
    private static int checks;
    private static void check(boolean passed, String message) {
        checks++;
        if (!passed) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(!GdxAudioDevices.outputTopologyChanged(List.of("speaker", "headphones"),
                List.of("headphones", "speaker")), "Route enumeration order is not a hotplug");
        check(!GdxAudioDevices.topologyHasOutput(Arrays.asList(null, " ")),
                "Empty native routes must be unavailable");
        GdxAudioDevices.OutputHotplugMonitor monitor = new GdxAudioDevices.OutputHotplugMonitor();
        check(!monitor.acceptTopology(List.of("speaker")), "First sample establishes the baseline");
        check(monitor.outputAvailable(), "Speaker route is available");
        check(monitor.acceptTopology(List.of()), "Disconnect is reported");
        check(!monitor.outputAvailable(), "Disconnect marks output unavailable");
        check(!monitor.acceptTopology(List.of()), "Repeated disconnect is not a change");
        check(monitor.acceptTopology(List.of("headphones")), "Reconnect is reported");
        check(monitor.outputAvailable(), "Reconnect restores output availability");

        AtomicInteger reads = new AtomicInteger();
        AtomicReference<Runnable> worker = new AtomicReference<>();
        Properties preferences = new Properties();
        preferences.setProperty(GdxAudioDevices.OUTPUT_KEY, "saved-route");
        GdxAudioDevices.OutputHotplugMonitor scheduled = new GdxAudioDevices.OutputHotplugMonitor(
                () -> { reads.incrementAndGet(); return List.of("speaker"); }, worker::set);
        check(!scheduled.update(preferences, 0), "Scheduling alone is not a topology change");
        check(reads.get() == 0 && worker.get() != null, "Route enumeration stays off render update");
        worker.getAndSet(null).run();
        check(!scheduled.update(preferences, 0) && reads.get() == 1,
                "Completed initial poll establishes the baseline");
        check(!scheduled.update(preferences, 1) && reads.get() == 1 && worker.get() != null,
                "Later polls also use the worker");
        check("saved-route".equals(preferences.getProperty(GdxAudioDevices.OUTPUT_KEY)),
                "Hotplug observation preserves preferences");
        System.out.println("Audio topology: " + checks + " checks passed; physical playback requires device validation");
    }
}
