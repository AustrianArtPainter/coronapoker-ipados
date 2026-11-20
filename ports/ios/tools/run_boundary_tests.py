#!/usr/bin/env python3
"""Execute original security/state contracts with mobile implementations first."""
from pathlib import Path
from toolchain import jdk_home, game_version
import subprocess
import argparse

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--mobile-classes", type=Path, default=PORT / "build/game/classes",
                    help="A complete compiled mobile class snapshot; its ownership and version are verified")
args = parser.parse_args()
mobile_classes = args.mobile_classes.resolve()
JAVA = jdk_home() / "bin"
REPOSITORY = ROOT / ".m2/repository"
DEPENDENCIES = [
    "org/junit/platform/junit-platform-launcher/1.10.2/junit-platform-launcher-1.10.2.jar",
    "org/junit/platform/junit-platform-engine/1.10.2/junit-platform-engine-1.10.2.jar",
    "org/junit/platform/junit-platform-commons/1.10.2/junit-platform-commons-1.10.2.jar",
    "org/junit/jupiter/junit-jupiter-api/5.10.2/junit-jupiter-api-5.10.2.jar",
    "org/junit/jupiter/junit-jupiter-engine/5.10.2/junit-jupiter-engine-5.10.2.jar",
    "org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar",
    "org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar",
    "com/badlogicgames/gdx/gdx/1.14.2/gdx-1.14.2.jar",
    "com/badlogicgames/gdx/gdx-freetype/1.14.2/gdx-freetype-1.14.2.jar",
]
paths = [mobile_classes, ROOT / "modules/coronapoker-core/target/test-classes",
         ROOT / "modules/coronapoker-gdx/target/test-classes", ROOT / "modules/coronapoker-gdx/target/classes",
         ROOT / "modules/coronapoker-assets/target/classes", *[REPOSITORY / path for path in DEPENDENCIES]]
# The mobile classes reference RoboVM bridge types even with diagnostics off.
# Supply their definitions to the host verifier; no native method is invoked.
runtime = PORT / ".cache/toolchains/robovm-3.0.0-SNAPSHOT/lib"
paths += [runtime / name for name in ["robovm-rt.jar", "robovm-bro-bridge.jar",
                                    "robovm-objc.jar", "robovm-cocoatouch.jar"]]
missing = [str(path) for path in paths if not path.exists()]
missing += [str(mobile_classes / (name + ".class")) for name in [
    "com/tonikelope/coronapoker/core/ApplicationMetadata",
    "com/tonikelope/coronapoker/table/TableSnapshot",
    "com/tonikelope/coronapoker/Crupier",
    "com/tonikelope/coronapoker/gdx/CoronaPokerGdxTable",
    "com/tonikelope/coronapoker/gdx/GdxFrontendScreen",
] if not (mobile_classes / (name + ".class")).is_file()]
if missing:
    raise RuntimeError("Missing mobile/baseline test dependencies: " + ", ".join(missing))
classes = PORT / "build/boundary-tests"
classes.mkdir(parents=True, exist_ok=True)
cp = ":".join(map(str, paths))
original = ROOT / "modules/coronapoker-gdx/src/test/java/com/tonikelope/coronapoker/gdx/GdxTableViewStateTest.java"
text = original.read_text()
desktop_assertion = "        assertFalse(GdxImageClipboard.copy((java.awt.Image) null));\n"
if text.count(desktop_assertion) != 1: raise RuntimeError("Desktop clipboard assertion changed; re-audit")
mobile_test = classes / "generated/com/tonikelope/coronapoker/gdx/GdxTableViewStateTest.java"
mobile_test.parent.mkdir(parents=True, exist_ok=True)
mobile_test.write_text(text.replace(desktop_assertion, ""))
print("Original state/security tests; only the AWT-null clipboard assertion is omitted. UIKit clipboard requires device validation.", flush=True)
subprocess.run([str(JAVA / "javac"), "--release", "17", "-cp", cp, "-d", str(classes), str(PORT / "tools/BoundaryTests.java"), str(mobile_test)], check=True)
subprocess.run([str(JAVA / "java"),
               "-Dcoronapoker.validation.expectedVersion=" + game_version(),
               "-Dcoronapoker.validation.mobileClasses=" + str(mobile_classes),
               "-cp", str(classes) + ":" + cp, "BoundaryTests",
               "com.tonikelope.coronapoker.core.AvatarImageValidatorTest",
               "com.tonikelope.coronapoker.core.LobbySessionTest",
               "com.tonikelope.coronapoker.core.game.GameStateTest",
               "com.tonikelope.coronapoker.core.game.CorePlayerControllerTest",
               "com.tonikelope.coronapoker.core.game.HotJoinSnapshotCodecV1Test",
               "com.tonikelope.coronapoker.core.game.HotJoinVisualEventCodecV1Test",
               "com.tonikelope.coronapoker.core.network.ConnectionAdmissionGuardTest",
               "com.tonikelope.coronapoker.core.network.AuthenticatedHotJoinAdmissionGuardTest",
               "com.tonikelope.coronapoker.core.network.InboundTrafficGuardTest",
               "com.tonikelope.coronapoker.core.network.WireFrameCodecTest",
               "com.tonikelope.coronapoker.table.TableSnapshotMapperTest",
               "com.tonikelope.coronapoker.HotJoinPresentationCutoverTest",
               "com.tonikelope.coronapoker.CrupierHandReadyTest",
               "com.tonikelope.coronapoker.ImmediateRebuyExposureTest",
               "com.tonikelope.coronapoker.gdx.GdxTableTerminationWiringTest",
               # This original method exercises the 26.16 spectator eligibility
               # fix directly against the generated mobile table. Other methods
               # in its class inspect desktop source paths and are covered by
               # upstream Maven, not claimed as mobile implementation checks.
               "com.tonikelope.coronapoker.gdx.GdxTableShortcutTest#bustedSpectatorsCanRebuyButWarmingAndExitedPlayersCannot",
               "com.tonikelope.coronapoker.gdx.GdxNetworkBlockCacheTest",
               "com.tonikelope.coronapoker.gdx.GdxNetworkBlockDialogLayoutTest",
               "com.tonikelope.coronapoker.gdx.GdxPointerHoldTest",
               "com.tonikelope.coronapoker.gdx.GdxScrollbarDragTest",
               "com.tonikelope.coronapoker.gdx.GdxTableViewStateTest"], check=True)
