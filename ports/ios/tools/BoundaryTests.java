import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Run the existing source contracts against the generated mobile variants. */
public final class BoundaryTests {
    public static void main(String[] args) {
        var request = LauncherDiscoveryRequestBuilder.request();
        for (String test : args) request.selectors(DiscoverySelectors.selectClass(test));
        var summary = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(summary);
        launcher.execute(request.build());
        summary.getSummary().printTo(new java.io.PrintWriter(System.out));
        summary.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
        if (summary.getSummary().getTestsFailedCount() != 0
                || summary.getSummary().getTestsSucceededCount() == 0) System.exit(1);
    }
}
