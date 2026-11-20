#!/usr/bin/env python3
"""Launch the pinned original desktop GUI, with an external QA input driver."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import zipfile
from pathlib import Path
from toolchain import jdk_home, game_version

PORT=Path(__file__).resolve().parents[1]
ROOT=PORT.parents[1]
JDK=jdk_home()

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("mode",choices=["host","host-hotjoin","join"])
    parser.add_argument("address")
    parser.add_argument("--port",type=int,default=7250)
    parser.add_argument("--run-id",required=True)
    parser.add_argument("--hands",type=int,default=3)
    parser.add_argument("--seconds",type=int,default=600)
    parser.add_argument("--interactive", action="store_true",
                        help="Keep the original GUI input enabled and leave all table actions to its user")
    parser.add_argument("--rebuy", action="store_true", help="Enable original rebuy rules in a host-created QA room")
    parser.add_argument("--bots", type=int, help="Host bots, with one human seat reserved for the iPad; hot-join defaults to one")
    parser.add_argument("--think-seconds", type=int, default=10)
    parser.add_argument("--jar", type=Path, help="An unchanged official release JAR instead of the local Maven distribution")
    parser.add_argument("--expected-sha256", help="Release asset digest obtained from GitHub; required with --jar")
    args=parser.parse_args()
    if not args.run_id or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-" for c in args.run_id):
        parser.error("run-id requires ASCII letters, numbers, underscores or hyphens")
    if args.seconds < 1 or args.hands < 0 or not 1 <= args.port <= 65535 or not 10 <= args.think_seconds <= 120:
        parser.error("Use seconds >= 1, hands >= 0, a valid TCP port and think-seconds 10..120")
    if args.bots is not None and (args.mode == "join" or not 0 <= args.bots <= 8):
        parser.error("bots requires a host mode and a count 0..8")
    if args.mode == "host-hotjoin" and args.bots == 0:
        parser.error("host-hotjoin requires at least one bot before the iPad arrives")
    if bool(args.jar) != bool(args.expected_sha256) or (args.expected_sha256 and not re.fullmatch(r"[a-fA-F0-9]{64}", args.expected_sha256)):
        parser.error("--jar requires --expected-sha256 with the 64-digit release SHA-256")
    version=game_version()
    jar=args.jar.resolve() if args.jar else ROOT/f"target/CoronaPoker_{version}.jar"
    jar_sha256 = hashlib.sha256(jar.read_bytes()).hexdigest()
    if args.expected_sha256 and jar_sha256 != args.expected_sha256.lower():
        raise RuntimeError("The supplied desktop JAR does not match the official release digest")
    core=ROOT/f"modules/coronapoker-core/target/coronapoker-core-{version}.jar"
    core_classes=[]
    with zipfile.ZipFile(jar) as gui:
        metadata = gui.read("META-INF/coronapoker-version.properties").decode("utf-8")
        embedded_version = next((line.split("=", 1)[1].strip() for line in metadata.splitlines()
                                 if line.strip().startswith("version=")), None)
        if embedded_version != version:
            raise RuntimeError("The desktop JAR's embedded version differs from the pinned iPad protocol")
        if not args.jar:
            with zipfile.ZipFile(core) as engine:
                core_classes=[name for name in engine.namelist() if name.endswith(".class")]
                if any(gui.read(name)!=engine.read(name) for name in core_classes):
                    raise RuntimeError("The desktop distribution does not contain the verified original core")
    classes=PORT/"build/desktop-gui-peer/classes";classes.mkdir(parents=True,exist_ok=True)
    subprocess.run([str(JDK/"bin/javac"),"--release","17","-cp",str(jar),"-d",str(classes),str(PORT/"tools/DesktopGuiPeerProbe.java")],check=True)
    print(json.dumps({"originalDesktopJarSHA256":jar_sha256,
                      "desktopVersion":version,"originalCoreClassesMatched":len(core_classes),"mobileAdaptersOnClasspath":False,
                      "distributionSource":"official-release" if args.jar else "local-upstream-build",
                      "releaseDigestMatched":bool(args.jar),
                      "interactive":args.interactive,"hostRebuy":args.rebuy}),flush=True)
    environment = os.environ.copy()
    environment["CP_DESKTOP_EXPECTED_VERSION"] = version
    environment["CP_DESKTOP_RUN_ID"] = args.run_id
    environment["CP_DESKTOP_INTERACTIVE"] = "1" if args.interactive else "0"
    environment["CP_DESKTOP_REBUY"] = "1" if args.rebuy else "0"
    environment["CP_DESKTOP_THINK_SECONDS"] = str(args.think_seconds)
    if args.bots is not None:
        environment["CP_DESKTOP_BOTS"] = str(args.bots)
    # GDX uses its native GLFW window. ImageIO must not start a second macOS
    # AWT event loop inside GLFW's event poll when decoding desktop GIFs.
    subprocess.run([str(JDK/"bin/java"),"-XstartOnFirstThread","-Dcoronapoker.macos.firstThread=true","-Djava.awt.headless=true","-cp",str(classes)+":"+str(jar),
        "com.tonikelope.coronapoker.gdx.DesktopGuiPeerProbe",args.mode,args.address,str(args.port),
        str(PORT/"build/desktop-gui-peer/runs"/args.run_id),str(args.hands),str(args.seconds)],env=environment,check=True)

if __name__=="__main__":main()
