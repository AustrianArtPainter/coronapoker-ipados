package com.tonikelope.coronapoker.ios;

import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.uikit.UIApplication;
import org.robovm.apple.uikit.UIApplicationDelegateAdapter;
import org.robovm.apple.uikit.UIApplicationLaunchOptions;
import org.robovm.apple.uikit.UISceneConfiguration;
import org.robovm.apple.uikit.UISceneConnectionOptions;
import org.robovm.apple.uikit.UISceneSession;
import org.robovm.objc.annotation.CustomClass;

@CustomClass("CoronaPokerCoreProbeApp")
public final class IOSCoreProbe extends UIApplicationDelegateAdapter {
    @Override public boolean didFinishLaunching(UIApplication application, UIApplicationLaunchOptions options) { return true; }
    @Override public UISceneConfiguration getConfigurationForConnectingSceneSession(UIApplication app, UISceneSession session, UISceneConnectionOptions options) {
        UISceneConfiguration config = new UISceneConfiguration("Core validation", session.getRole());
        config.setDelegateClass(IOSCoreProbeScene.class);
        return config;
    }
    public static void main(String[] args) {
        try (NSAutoreleasePool pool = new NSAutoreleasePool()) { UIApplication.main(args, null, IOSCoreProbe.class); }
    }
}
