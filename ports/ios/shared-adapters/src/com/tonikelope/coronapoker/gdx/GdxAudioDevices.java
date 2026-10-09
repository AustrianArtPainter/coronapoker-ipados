package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.ios.IOSPlatform;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/** Route labels and capture selection from the device's actual audio session. */
final class GdxAudioDevices {
    static final String OUTPUT_KEY = "gdx_audio_output_device";
    static final String CAPTURE_KEY = "audio_capture_device";
    static final class OutputHotplugMonitor {
        private List<String> topology;
        private float elapsed;
        boolean update(Properties properties, float delta) {
            elapsed += Math.max(0, delta);
            if (topology != null && elapsed < 1) return false;
            elapsed = 0;
            List<String> current = Arrays.asList(IOSPlatform.audioOutputs());
            boolean changed = topology != null && !topology.equals(current);
            topology = List.copyOf(current);
            return changed;
        }
        boolean outputAvailable() { return topology == null || !topology.isEmpty(); }
    }
    static void refreshCaptureDevicesAsync() {}
    static boolean hasCaptureDevices() { return IOSPlatform.microphoneInputs().length > 0; }
    static boolean topologyHasOutput(List<String> ports) { return ports != null && !ports.isEmpty(); }
    static String outputLabel(Properties properties, GdxGameText text) {
        String[] ports = IOSPlatform.audioOutputs();
        return ports.length == 0 ? "iOS audio route" : label(ports[0]);
    }
    static String captureLabel(Properties properties, GdxGameText text) {
        String selected = properties.getProperty(CAPTURE_KEY, "");
        for (String input : IOSPlatform.microphoneInputs()) if (uid(input).equals(selected)) return label(input);
        return text.translate("gdx.settings.audio.default");
    }
    static boolean cycleOutput(Properties properties) { return false; }
    static boolean adjustOutput(Properties properties, int direction) { return false; }
    static void applyConfiguredOutput(Properties properties) { properties.setProperty(OUTPUT_KEY, ""); }
    static boolean outputSelectionChanged(String previous, String restored) { return !Objects.equals(previous, restored); }
    static void cycleCapture(Properties properties) { adjustCapture(properties, 1); }
    static void adjustCapture(Properties properties, int direction) {
        List<String> inputs = new ArrayList<>(); inputs.add("");
        for (String input : IOSPlatform.microphoneInputs()) inputs.add(uid(input));
        int current = inputs.indexOf(properties.getProperty(CAPTURE_KEY, ""));
        int next = Math.floorMod((current < 0 ? 0 : current) + direction, inputs.size());
        String selected = inputs.get(next);
        if (IOSPlatform.useMicrophone(selected)) properties.setProperty(CAPTURE_KEY, selected);
    }
    private static String uid(String port) { int split = port.indexOf('\t'); return split < 0 ? port : port.substring(0, split); }
    private static String label(String port) { int split = port.indexOf('\t'); return split < 0 ? port : port.substring(split + 1); }
    private GdxAudioDevices() {}
}
