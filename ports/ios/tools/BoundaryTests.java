import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Run the existing source contracts against the generated mobile variants. */
public final class BoundaryTests {
    public static void main(String[] args) {
        String expected = System.getProperty("coronapoker.validation.expectedVersion");
        if (expected == null || !expected.equals(
                com.tonikelope.coronapoker.core.ApplicationMetadata.VERSION)) {
            throw new AssertionError("Mobile runtime version does not match the requested source version");
        }
        try {
            var mobile = java.nio.file.Path.of(System.getProperty(
                    "coronapoker.validation.mobileClasses")).toRealPath();
            String classes = System.getProperty("coronapoker.validation.mobileClassNames",
                    "com.tonikelope.coronapoker.Crupier,"
                    + "com.tonikelope.coronapoker.gdx.CoronaPokerGdxTable,"
                    + "com.tonikelope.coronapoker.gdx.GdxFrontendScreen");
            for (String name : classes.split(",")) {
                var source = java.nio.file.Path.of(Class.forName(name, false,
                        BoundaryTests.class.getClassLoader())
                        .getProtectionDomain().getCodeSource().getLocation()
                        .toURI()).toRealPath();
                if (!mobile.equals(source)) {
                    throw new AssertionError("Contract loaded desktop instead of mobile implementation: " + name);
                }
            }
        } catch (Exception failure) {
            throw new AssertionError("Unable to verify mobile class ownership", failure);
        }
        System.out.println("Mobile contract version: " + expected);
        var request = LauncherDiscoveryRequestBuilder.request();
        for (String test : args) {
            System.out.println("Contract: " + test);
            int method = test.indexOf('#');
            request.selectors(method < 0 ? DiscoverySelectors.selectClass(test)
                    : DiscoverySelectors.selectMethod(test.substring(0, method),
                            test.substring(method + 1)));
        }
        var summary = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(summary);
        launcher.execute(request.build());
        summary.getSummary().printTo(new java.io.PrintWriter(System.out));
        summary.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
        if (summary.getSummary().getTotalFailureCount() != 0
                || summary.getSummary().getContainersFailedCount() != 0
                || summary.getSummary().getTestsSucceededCount() == 0) System.exit(1);
    }
}
