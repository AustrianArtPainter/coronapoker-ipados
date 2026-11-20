package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.ios.IOSPlatform;
import com.tonikelope.coronapoker.ios.SilentAudio;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Route labels and capture selection from the device's actual audio session. */
final class GdxAudioDevices {
    static final String OUTPUT_KEY = "gdx_audio_output_device";
    static final String CAPTURE_KEY = "audio_capture_device";
    private static final Executor OUTPUT_DISCOVERY = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "CoronaPoker-iOS-audio-route");
        worker.setDaemon(true);
        return worker;
    });
    static final class OutputHotplugMonitor {
        private final Supplier<List<String>> topologySource;
        private final Executor topologyExecutor;
        private List<String> topology;
        private CompletableFuture<List<String>> pendingTopology;
        private boolean outputAvailable = true;
        private float elapsed;

        OutputHotplugMonitor() {
            this(() -> Arrays.asList(SilentAudio.outputs()),
                    OUTPUT_DISCOVERY);
        }
        OutputHotplugMonitor(Supplier<List<String>> source, Executor executor) {
            topologySource = Objects.requireNonNull(source, "topologySource");
            topologyExecutor = Objects.requireNonNull(executor, "topologyExecutor");
        }
        boolean update(Properties properties, float delta) {
            elapsed += Math.max(0, delta);
            if (pendingTopology == null && (topology == null || elapsed >= 1)) {
                elapsed = 0;
                pendingTopology = CompletableFuture.supplyAsync(topologySource, topologyExecutor)
                        .exceptionally(unavailable -> List.of());
            }
            CompletableFuture<List<String>> completed = pendingTopology;
            if (completed == null || !completed.isDone()) return false;
            pendingTopology = null;
            // AVAudioSession owns output routing; report its actual topology
            // without trying to reopen the desktop OpenAL backend.
            return acceptTopology(completed.join());
        }
        boolean acceptTopology(List<String> devices) {
            List<String> current = normalizedTopology(devices);
            boolean changed = topology != null && outputTopologyChanged(topology, current);
            topology = List.copyOf(current);
            outputAvailable = !current.isEmpty();
            return changed;
        }
        boolean outputAvailable() { return outputAvailable; }
    }
    static void refreshCaptureDevicesAsync() {}
    static boolean hasCaptureDevices() { return SilentAudio.inputs().length > 0; }
    static boolean topologyHasOutput(List<String> ports) { return !normalizedTopology(ports).isEmpty(); }
    static boolean outputTopologyChanged(List<String> previous, List<String> current) {
        return !normalizedTopology(previous).equals(normalizedTopology(current));
    }
    private static List<String> normalizedTopology(List<String> ports) {
        return ports == null ? List.of() : ports.stream().filter(Objects::nonNull)
                .map(String::strip).filter(port -> !port.isEmpty()).distinct().sorted().toList();
    }
    // AVAudioSession exposes the active output route. Users choose a different
    // output in Control Center. The game can select available microphone inputs.
    static List<String> outputChoices() {
        String[] outputs = SilentAudio.outputs();
        return List.of(outputs.length == 0 ? "" : outputs[0]);
    }
    static int selectedOutputIndex(Properties properties) { return 0; }
    static boolean selectOutput(Properties properties, String device) {
        if (!outputChoices().contains(device)) return false;
        applyConfiguredOutput(properties);
        return true;
    }
    static List<String> captureChoices() {
        List<String> choices = new ArrayList<>();
        choices.add("");
        choices.addAll(Arrays.asList(SilentAudio.inputs()));
        return choices;
    }
    private static int selectedInput(List<String> choices, Properties properties) {
        String selected = properties.getProperty(CAPTURE_KEY, "");
        for (int i = 1; i < choices.size(); i++) {
            if (uid(choices.get(i)).equals(selected)) return i;
        }
        return 0;
    }
    static int selectedCaptureIndex(Properties properties) {
        return selectedInput(captureChoices(), properties);
    }
    static void selectCapture(Properties properties, String port) {
        String selected = uid(port);
        if (IOSPlatform.useMicrophone(selected)) properties.setProperty(CAPTURE_KEY, selected);
    }
    static String deviceLabel(String port, GdxGameText text) {
        return port.isEmpty() ? text.translate("gdx.settings.value.system_default") : label(port);
    }
    static String outputLabel(Properties properties, GdxGameText text) {
        return deviceLabel(outputChoices().get(0), text);
    }
    static String captureLabel(Properties properties, GdxGameText text) {
        List<String> choices = captureChoices();
        return deviceLabel(choices.get(selectedInput(choices, properties)), text);
    }
    static boolean cycleOutput(Properties properties) { return false; }
    static boolean adjustOutput(Properties properties, int direction) { return false; }
    static void applyConfiguredOutput(Properties properties) { properties.setProperty(OUTPUT_KEY, ""); }
    static boolean outputSelectionChanged(String previous, String restored) { return !Objects.equals(previous, restored); }
    static void cycleCapture(Properties properties) { adjustCapture(properties, 1); }
    static void adjustCapture(Properties properties, int direction) {
        List<String> choices = captureChoices();
        int next = Math.floorMod(selectedInput(choices, properties) + Integer.signum(direction), choices.size());
        selectCapture(properties, choices.get(next));
    }
    private static String uid(String port) { int split = port.indexOf('\t'); return split < 0 ? port : port.substring(0, split); }
    private static String label(String port) { int split = port.indexOf('\t'); return split < 0 ? port : port.substring(split + 1); }
    private GdxAudioDevices() {}
}
