#!/usr/bin/env python3
"""Compare real upstream table queries and exact iOS output without App/AOT/UI."""
import hashlib
import json
from pathlib import Path
import subprocess

from prepare_table_queries import REPLACEMENTS, prepare
import prepare_game_sources as generator
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
OUTPUT = PORT / "build/table-query-tests"
UPSTREAM = ROOT / "modules/coronapoker-gdx/src/main/java/com/tonikelope/coronapoker/gdx/CoronaPokerGdxTable.java"
BUILD = PORT / "build/game-simulator"
GENERATED = PORT / "build/game/generated-src/com/tonikelope/coronapoker/gdx/CoronaPokerGdxTable.java"
SIGNATURES = (
    "    static boolean isActionableTurn(TableSnapshot snapshot, String nickname) {",
    "    static boolean isActionableTurn(TableSnapshot snapshot, String nickname,",
    "    private boolean canKickTimedOutPlayer(String nickname) {",
    "    private String availableDeck(String deck) {",
    "    private Texture tableAvatar(String nickname) {",
    "    private TableSnapshot.PlayerSnapshot livePlayer(String nickname) {",
    "    static boolean timedOutKickEligible(",
)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def method(text, signature):
    if text.count(signature) != 1:
        raise RuntimeError("Probe requires one actual method: " + signature)
    start = text.index(signature)
    opening = text.index("{", start)
    depth = 1
    end = opening + 1
    # These exact audited query/helper bodies have no braces in comments/strings.
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


def seam(text, name):
    return """package com.tonikelope.coronapoker.gdx;
import com.tonikelope.coronapoker.core.LobbyParticipant;
import com.tonikelope.coronapoker.table.TableSnapshot;
import com.tonikelope.coronapoker.gdx.TableQueryProbe.Texture;
import com.tonikelope.coronapoker.gdx.TableQueryProbe.TextureFilter;
import com.tonikelope.coronapoker.gdx.TableQueryProbe.Gdx;
import java.util.List;
final class """ + name + """ extends TableQueryProbe.State {
 public boolean actionable(TableSnapshot snapshot,String nickname){return isActionableTurn(snapshot,nickname);}
 public boolean actionable(TableSnapshot snapshot,String nickname,boolean unavailable){return isActionableTurn(snapshot,nickname,unavailable);}
 public boolean kick(String nickname){return canKickTimedOutPlayer(nickname);}
 public String deck(String name){return availableDeck(name);}
 public Texture avatar(String name){return tableAvatar(name);}
""" + "\n".join(method(text, signature) for signature in SIGNATURES) + "\n}\n"


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    classes = OUTPUT / "classes"
    classes.mkdir(exist_ok=True)
    original_hash = digest(UPSTREAM)
    original = UPSTREAM.read_text()
    converted = prepare(original)
    # Assert the converter changed precisely the owned methods and nothing else.
    restored = converted
    for _, old, new in REPLACEMENTS:
        assert restored.count(new) == 1
        restored = restored.replace(new, old, 1)
    assert restored == original
    invalid = [converted]
    for _, old, _ in REPLACEMENTS:
        invalid.extend((original.replace(old, old + old, 1), original.replace(old, old.replace("return", "return ", 1), 1)))
    for changed in invalid:
        try:
            prepare(changed)
        except RuntimeError:
            pass
        else:
            raise AssertionError("Converter accepted stale/repeated/missing ownership")
    sources = []
    for text, name in ((original, "OriginalTableQueries"), (converted, "MobileTableQueries")):
        path = OUTPUT / (name + ".java")
        path.write_text(seam(text, name))
        sources.append(path)
    m2 = ROOT / ".m2/repository"
    sdk = PORT / ".cache/toolchains/robovm-3.0.0-SNAPSHOT/lib"
    # Packaged App jars may be resource-only AOT inputs. Use pinned full Java
    # compile dependencies, never the installed App's stripped class archives.
    libraries = [m2 / "com/badlogicgames/gdx/gdx/1.14.2/gdx-1.14.2.jar",
                 m2 / "com/badlogicgames/gdx/gdx-freetype/1.14.2/gdx-freetype-1.14.2.jar",
                 PORT / ".cache/downloads/gdx-backend-robovm-metalangle-1.14.2.jar",
                 *[sdk / name for name in ("robovm-rt.jar", "robovm-bro-bridge.jar", "robovm-objc.jar", "robovm-cocoatouch.jar")]]
    if not all(path.is_file() for path in libraries) or not (BUILD / "classes").is_dir():
        raise RuntimeError("Existing simulator Java classes/dependencies are required; this probe does not build the App")
    classpath = ":".join(map(str, [BUILD / "classes", *libraries]))
    java = jdk_home() / "bin"
    subprocess.run([str(java / "javac"), "--release", "17", "-cp", classpath, "-d", str(classes),
                    *map(str, sources), str(PORT / "tools/TableQueryProbe.java")], cwd=OUTPUT, check=True)
    result = subprocess.run([str(java / "java"), "-Xmx256m", "-XX:-DoEscapeAnalysis", "-cp", str(classes) + ":" + classpath,
                             "com.tonikelope.coronapoker.gdx.TableQueryProbe"], text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError("Actual table query comparison failed:\n" + result.stdout + result.stderr)
    report = json.loads(result.stdout)
    # Exercise the same Java 17 -> Java 8 adapter as the App build, privately.
    # Inspect only our rewritten method: production texture/JNI code remains
    # outside the fixture, and no native compilation or App is launched.
    raw_jar, native_jar = OUTPUT / "original.jar", OUTPUT / "native.jar"
    subprocess.run([str(java / "jar"), "--create", "--file", str(raw_jar), "-C", str(classes), "."],
                   cwd=OUTPUT, check=True)
    subprocess.run([str(java / "java"), "-jar", str(PORT / ".cache/downloads/jvmdowngrader-2.0.1-all.jar"),
                    "--noColor", "-c", "52", "downgrade", "--target", str(raw_jar), "-", "--classpath", classpath,
                    "shade", "--prefix", "com/tonikelope/coronapoker/ios/compat", "--target", "-", str(native_jar)],
                   cwd=OUTPUT, check=True)
    disassembly = subprocess.run([str(java / "javap"), "-p", "-c", "-verbose", "-classpath", str(native_jar),
                                  "com.tonikelope.coronapoker.gdx.MobileTableQueries"],
                                 text=True, capture_output=True, check=True).stdout
    assert "major version: 52" in disassembly
    avatar = disassembly.split("  private com.tonikelope.coronapoker.gdx.TableQueryProbe$Texture tableAvatar(java.lang.String);", 1)[1]
    avatar = avatar.split("\n  private ", 1)[0]
    assert "java/util/List.get:(I)" in avatar
    assert "java/util/List.iterator:" not in avatar and "java/util/Iterator." not in avatar
    assert avatar.count("Method com/tonikelope/coronapoker/gdx/TableQueryProbe$Chat.snapshot:") == 1
    (OUTPUT / "MobileTableQueries.javap.txt").write_text(disassembly)
    # Run the actual complete generation pipeline into a private port. Its tools
    # symlink is read-only input; derivation metadata and generated files remain
    # here, so no shared source or compiler directory is changed.
    if not GENERATED.is_file():
        raise RuntimeError("Existing prepared table is required for the private complete-table compile")
    prepared_hash = digest(GENERATED)
    generator_hash = digest(PORT / "tools/prepare_game_sources.py")
    generator_text = (PORT / "tools/prepare_game_sources.py").read_text()
    integration = "    text = prepare_table_queries(text)\n    text = prepare_scene(text, replace_method, PORT)"
    assert generator_text.count(integration) == 1, "Queries must precede existing projection migration"
    private_port = OUTPUT / "generator-port"
    private_port.mkdir(exist_ok=True)
    link = private_port / "tools"
    if not link.exists():
        link.symlink_to((PORT / "tools").resolve(), target_is_directory=True)
    assert link.resolve() == (PORT / "tools").resolve()
    generator.PORT = private_port
    generator.GENERATED = private_port / "generated-src"
    generator.prepare()
    private = generator.GENERATED / "com/tonikelope/coronapoker/gdx/CoronaPokerGdxTable.java"
    final_text = private.read_text()
    for name, _, rewritten in REPLACEMENTS:
        if name == "canKickTimedOutPlayer":
            rewritten = rewritten.replace("livePlayer(nickname)", "mobileScenePlayer(nickname)")
        assert final_text.count(rewritten) == 1, "Final pipeline changed query output: " + name
    full_classes = OUTPUT / "full-table/classes"
    full_classes.mkdir(parents=True, exist_ok=True)
    uppercase = PORT / "shared-adapters/src/com/tonikelope/coronapoker/gdx/IOSTableUppercaseCache.java"
    attribution = PORT / "shared-adapters/src/com/tonikelope/coronapoker/ios/IOSFrameAttribution.java"
    subprocess.run([str(java / "javac"), "--release", "17", "-cp", classpath, "-d", str(full_classes),
                    str(private), str(uppercase), str(attribution)], cwd=OUTPUT, check=True)
    report.update(oracle="Method bodies extracted from the actual current upstream file, not converter OLD constants",
                  upstreamSHA256=original_hash, converterSHA256=digest(PORT / "tools/prepare_table_queries.py"),
                  generatedMethodBodiesCompiled=len(SIGNATURES), failClosedOwnershipCases=len(invalid),
                  privateJava8BytecodeAdaptation=True, adaptedAvatarContainsNoIterator=True,
                  adaptedAvatarSnapshotCalls=1,
                  completePreparedTableCompiled=True, actualFullGeneratorRunPrivately=True,
                  queryThenProjectionMigrationVerified=True,
                  privateGeneratedTableSHA256=digest(private), generatorSHA256=generator_hash,
                  desktopSourceUnchanged=digest(UPSTREAM) == original_hash,
                  sharedPreparedSourceUnchanged=digest(GENERATED) == prepared_hash,
                  appBuildOrAOTRun=False)
    assert report["desktopSourceUnchanged"] and report["sharedPreparedSourceUnchanged"]
    assert digest(PORT / "tools/prepare_game_sources.py") == generator_hash
    (OUTPUT / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
