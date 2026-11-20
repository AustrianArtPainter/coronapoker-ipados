import org.robovm.compiler.log.Logger;
import org.robovm.compiler.util.Executor;

/** Verify subprocess capture is complete before using the derived compiler. */
public final class HostToolchainProbe {
    public static void main(String[] args) throws Exception {
        String expected = "/Applications/Xcode.app/Contents/Developer";
        for (int i = 0; i < 50; i++) {
            String actual = new Executor(Logger.NULL_LOGGER, "/usr/bin/xcode-select").args("--print-path").execCapture();
            if (!expected.equals(actual)) throw new IllegalStateException("Incomplete Xcode path capture: " + actual);
        }
        System.out.println("{\"xcodePathCapture\":true,\"iterations\":50}");
    }
}
