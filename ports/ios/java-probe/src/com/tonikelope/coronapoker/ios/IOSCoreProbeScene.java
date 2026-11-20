package com.tonikelope.coronapoker.ios;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.robovm.apple.coregraphics.CGRect;
import org.robovm.apple.foundation.NSFileManager;
import org.robovm.apple.foundation.NSOperationQueue;
import org.robovm.apple.foundation.NSSearchPathDirectory;
import org.robovm.apple.foundation.NSSearchPathDomainMask;
import org.robovm.apple.uikit.UIColor;
import org.robovm.apple.uikit.UIFont;
import org.robovm.apple.uikit.UILabel;
import org.robovm.apple.uikit.UIScene;
import org.robovm.apple.uikit.UISceneConnectionOptions;
import org.robovm.apple.uikit.UISceneSession;
import org.robovm.apple.uikit.UIViewController;
import org.robovm.apple.uikit.UIWindow;
import org.robovm.apple.uikit.UIWindowScene;
import org.robovm.apple.uikit.UIWindowSceneDelegateAdapter;
import org.robovm.objc.annotation.CustomClass;

@CustomClass("CoronaPokerCoreProbeScene")
public final class IOSCoreProbeScene extends UIWindowSceneDelegateAdapter {
    private UILabel label;
    @Override public void willConnect(UIScene scene, UISceneSession session, UISceneConnectionOptions options) {
        UIWindowScene windowScene = (UIWindowScene) scene;
        UIWindow window = new UIWindow(windowScene);
        UIViewController view = new UIViewController();
        view.getView().setBackgroundColor(UIColor.black());
        label = new UILabel(new CGRect(24, 50, windowScene.getScreen().getBounds().getWidth() - 48,
                                       windowScene.getScreen().getBounds().getHeight() - 100));
        label.setNumberOfLines(0);
        label.setTextColor(UIColor.white());
        label.setFont(UIFont.getSystemFont(16));
        label.setText("CoronaPoker · 原生 Java 核心验证\n\n正在检查原游戏加密、异步执行与 SQLite 事务。\n完整游戏尚在移植中。");
        view.getView().addSubview(label);
        window.setRootViewController(view);
        setWindow(window);
        window.makeKeyAndVisible();
        Thread worker = new Thread(this::validate, "coronapoker-ios-core-validation");
        worker.setDaemon(true);
        worker.start();
    }

    private void validate() {
        String report;
        Path documents = Path.of(NSFileManager.getDefaultManager().getURLsForDirectory(
                NSSearchPathDirectory.DocumentDirectory, NSSearchPathDomainMask.UserDomainMask).get(0).getPath());
        try {
            Files.createDirectories(documents);
            String crypto = ModernRuntimeProbe.run();
            String database = DatabaseRuntimeProbe.run(documents.resolve("java-jdbc-probe.sqlite").toString());
            report = "{\"deviceRuntime\":\"iOS arm64 AOT\",\"fullGameValidated\":false,\"crypto\":" + crypto + ",\"database\":" + database + "}";
        } catch (Throwable failure) {
            java.io.StringWriter buffer = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(buffer));
            report = "FAILED\n" + buffer;
        }
        try { Files.write(documents.resolve("java-probe-report.txt"), report.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception failure) { report += "\nReport write failed: " + failure; }
        final String visible = report;
        NSOperationQueue.getMainQueue().addOperation(() -> label.setText("CoronaPoker · 原生 Java 核心验证\n\n" + visible));
    }
}
