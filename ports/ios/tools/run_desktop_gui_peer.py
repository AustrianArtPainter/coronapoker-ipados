#!/usr/bin/env python3
"""Launch the pinned original desktop GUI, with an external QA input driver."""
import argparse
import hashlib
import json
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
    args=parser.parse_args()
    if not args.run_id or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-" for c in args.run_id):
        parser.error("run-id requires ASCII letters, numbers, underscores or hyphens")
    version=game_version()
    jar=ROOT/f"target/CoronaPoker_{version}.jar"
    core=ROOT/f"modules/coronapoker-core/target/coronapoker-core-{version}.jar"
    with zipfile.ZipFile(jar) as gui,zipfile.ZipFile(core) as engine:
        core_classes=[name for name in engine.namelist() if name.endswith(".class")]
        if any(gui.read(name)!=engine.read(name) for name in core_classes):
            raise RuntimeError("The desktop distribution does not contain the verified original core")
    classes=PORT/"build/desktop-gui-peer/classes";classes.mkdir(parents=True,exist_ok=True)
    subprocess.run([str(JDK/"bin/javac"),"--release","17","-cp",str(jar),"-d",str(classes),str(PORT/"tools/DesktopGuiPeerProbe.java")],check=True)
    print(json.dumps({"originalDesktopJarSHA256":hashlib.sha256(jar.read_bytes()).hexdigest(),
                      "desktopVersion":version,"originalCoreClassesMatched":len(core_classes),"mobileAdaptersOnClasspath":False}),flush=True)
    # GDX uses its native GLFW window. ImageIO must not start a second macOS
    # AWT event loop inside GLFW's event poll when decoding desktop GIFs.
    subprocess.run([str(JDK/"bin/java"),"-XstartOnFirstThread","-Dcoronapoker.macos.firstThread=true","-Djava.awt.headless=true","-cp",str(classes)+":"+str(jar),
        "com.tonikelope.coronapoker.gdx.DesktopGuiPeerProbe",args.mode,args.address,str(args.port),
        str(PORT/"build/desktop-gui-peer/runs"/args.run_id),str(args.hands),str(args.seconds)],check=True)

if __name__=="__main__":main()
