package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.tonikelope.coronapoker.CoreGameTableFactory;
import com.tonikelope.coronapoker.Crupier;
import com.tonikelope.coronapoker.StatsSync;
import com.tonikelope.coronapoker.core.*;
import com.tonikelope.coronapoker.core.game.ClasspathGameCinematicAssets;
import com.tonikelope.coronapoker.core.game.GameCinematicAssets;
import com.tonikelope.coronapoker.core.game.ModAwareGameCinematicAssets;
import com.tonikelope.coronapoker.core.media.ModMediaCatalog;
import com.tonikelope.coronapoker.core.network.NetworkLobbyGateway;
import com.tonikelope.coronapoker.ios.Ed25519Provider;
import com.tonikelope.coronapoker.ios.IOSPlatform;
import java.nio.file.Path;
import org.robovm.apple.foundation.*;
import org.robovm.apple.uikit.UIApplication;
import org.robovm.objc.annotation.CustomClass;

/** The existing shared core and GDX screens, hosted by the iOS native backend. */
@CustomClass("CoronaPokerIOSDelegate")
public final class IOSGdxLauncher extends IOSApplication.Delegate {
    private CoronaPokerApplication application;
    private NetworkLobbyGateway lobby;

    @Override protected IOSApplication createApplication() {
        // The opt-in baseline bypasses every game service and asset initializer.
        if ("1".equals(System.getenv("CP_IOS_BACKEND_PROBE"))) {
            IOSApplicationConfiguration baseline = new IOSApplicationConfiguration();
            baseline.orientationLandscape = true; baseline.orientationPortrait = false;
            baseline.preferredFramesPerSecond = 60; baseline.useAccelerometer = false;
            baseline.preventScreenDimming = true; baseline.hideHomeIndicator = true;
            baseline.useAudio = false;
            return new IOSApplication(new com.tonikelope.coronapoker.ios.BackendBaselineProbe(), baseline) {
                @Override protected com.badlogic.gdx.backends.iosrobovm.IOSAudio createAudio(IOSApplicationConfiguration ignored) {
                    return new com.tonikelope.coronapoker.ios.SilentAudio();
                }
            };
        }
        try {
            String previousTemporaryDirectory = System.getProperty("java.io.tmpdir");
            Path temporaryDirectory = IOSPlatform.temporaryDirectory();
            IOSPlatform.protectDirectory(temporaryDirectory.toString());
            System.setProperty("java.io.tmpdir", temporaryDirectory.toString());
            com.tonikelope.coronapoker.ios.IOSDiagnostics.beginRun();
            if ("1".equals(System.getenv("CP_IOS_VOICE_PROBE")))
                com.tonikelope.coronapoker.ios.IOSDiagnostics.report("ios-java-temp.txt",
                        "before=" + previousTemporaryDirectory + "\nafter="
                                + System.getProperty("java.io.tmpdir") + "\nnative=" + temporaryDirectory);
            Ed25519Provider.installIfMissing();
            String support = NSFileManager.getDefaultManager().getURLsForDirectory(
                    NSSearchPathDirectory.ApplicationSupportDirectory, NSSearchPathDomainMask.UserDomainMask).get(0).getPath();
            Path home = IOSGameValidation.home(Path.of(support, "CoronaPoker"));
            IOSPlatform.protectDirectory(home.toString());
            System.setProperty("user.home", home.toString());
            Path mediaHome = IOSGameValidation.ENABLED ? home
                    : com.tonikelope.coronapoker.ios.IOSDiagnostics.documents().resolve("CoronaPoker");
            IOSPlatform.protectDirectory(mediaHome.toString());
            System.setProperty("coronapoker.install.dir", mediaHome.toString());
            GdxDebugFile.install();
            com.tonikelope.coronapoker.DebugLog.install();
            application = CoronaPokerBootstrap.createApplication(home, false);
            application.start(); application.menuReady();
            PreferencesService preferences = application.service(PreferencesService.class);
            DatabaseService database = application.service(DatabaseService.class);
            ModMediaCatalog mod = GdxGamePresentationSettings.discoverInstalledMod();
            GdxGamePresentationSettings presentation = new GdxGamePresentationSettings(preferences, mod);
            configureModSounds(mod, presentation.language());
            GdxGameText text = new GdxGameText(preferences.properties().getProperty("lenguaje", "es"));
            GdxGameLogSink log = new GdxGameLogSink();
            IdentityTrustStore trust = new SqlIdentityTrustStore(database);
            StatsSyncService stats = new StatsSyncService(database,
                    () -> Boolean.parseBoolean(preferences.properties().getProperty("sync_stats_exclude_private", "true")),
                    () -> Boolean.parseBoolean(preferences.properties().getProperty("sync_stats_exclude_nicks_enabled", "false"))
                            ? StatsSync.parseExcludedNicks(preferences.properties().getProperty("sync_stats_exclude_nicks", "")) : java.util.Set.of());
            GameCinematicAssets bundledCinematics = new ClasspathGameCinematicAssets("cinematics/allin");
            GameCinematicAssets cinematics = mod.installed()
                    ? new ModAwareGameCinematicAssets(mod, bundledCinematics,
                            java.util.Arrays.stream(Crupier.ALLIN_CINEMATICS.getValue()).map(entry -> (String) entry[0]).toList())
                    : bundledCinematics;
            CoreGameTableFactory tables = new CoreGameTableFactory(database, text, log,
                    new GdxGameDialogSink(text), new GdxGameDecisionSink(text), presentation,
                    cinematics, mod.installed(),
                    preferences.properties(), trust);
            lobby = NetworkLobbyGateway.forCurrentUser(tables, new RecoverableGameRepository(database), trust,
                    () -> Boolean.parseBoolean(preferences.properties().getProperty("sync_stats_receive", "true")),
                    () -> Boolean.parseBoolean(preferences.properties().getProperty("sync_stats_share", "true")), stats);
            GdxApplicationShell shell = new GdxApplicationShell(60, application, lobby, log,
                    presentation, text, language -> {
                        text.setLanguage(language); configureModSounds(mod, language);
                    }, trust);
            IOSApplicationConfiguration config = new IOSApplicationConfiguration();
            config.orientationLandscape = true; config.orientationPortrait = false;
            config.preferredFramesPerSecond = 60; config.useAccelerometer = false;
            config.preventScreenDimming = false; config.hideHomeIndicator = true;
            config.overrideRingerSwitch = true;
            if (com.tonikelope.coronapoker.ios.SilentAudio.ENABLED) {
                config.useAudio = false;
                return new IOSApplication(new com.tonikelope.coronapoker.ios.IOSDiagnostics(shell), config) {
                    @Override protected com.badlogic.gdx.backends.iosrobovm.IOSAudio createAudio(IOSApplicationConfiguration ignored) {
                        return new com.tonikelope.coronapoker.ios.SilentAudio();
                    }
                };
            }
            return new IOSApplication(new com.tonikelope.coronapoker.ios.IOSDiagnostics(shell), config);
        } catch (Throwable failure) {
            com.tonikelope.coronapoker.ios.IOSDiagnostics.failure(failure);
            throw new IllegalStateException("Native CoronaPoker startup failed", failure);
        }
    }
    private static void configureModSounds(ModMediaCatalog mod, String language) {
        if (!mod.installed()) return;
        String selected = Crupier.ALLIN_SOUNDS.containsKey(language) ? language : "es";
        Crupier.ALLIN_SOUNDS_MOD = mod.soundCategory(selected, "allin", Crupier.ALLIN_SOUNDS.get(selected));
        Crupier.FOLD_SOUNDS_MOD = mod.soundCategory(selected, "fold", Crupier.FOLD_SOUNDS.get(selected));
        Crupier.SHOWDOWN_SOUNDS_MOD = mod.soundCategory(selected, "showdown", Crupier.SHOWDOWN_SOUNDS.get(selected));
        Crupier.LOSER_SOUNDS_MOD = mod.soundCategory(selected, "loser", Crupier.LOSER_SOUNDS.get(selected));
        Crupier.WINNER_SOUNDS_MOD = mod.soundCategory(selected, "winner", Crupier.WINNER_SOUNDS.get(selected));
    }
    @Override public void willTerminate(UIApplication ui) {
        super.willTerminate(ui);
        if (lobby != null) lobby.close();
        if (application != null) application.close();
    }
    public static void main(String[] args) {
        try (NSAutoreleasePool pool = new NSAutoreleasePool()) { UIApplication.main(args, null, IOSGdxLauncher.class); }
    }
}
