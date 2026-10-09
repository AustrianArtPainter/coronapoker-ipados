#!/usr/bin/env python3
"""Drive only the original desktop 25.79 jars, in an isolated test directory."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
JDK = jdk_home()

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("mode",choices=["host","join"])
    parser.add_argument("address")
    parser.add_argument("--port",type=int,default=7250)
    parser.add_argument("--run-id",required=True)
    parser.add_argument("--hands",type=int,default=3)
    parser.add_argument("--seconds",type=int,default=600)
    args=parser.parse_args()
    if not args.run_id or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-" for c in args.run_id):
        parser.error("run-id must contain only ASCII letters, numbers, underscores or hyphens")
    classes=PORT/"build/desktop-peer/classes";classes.mkdir(parents=True,exist_ok=True)
    core=ROOT/"modules/coronapoker-core/target/coronapoker-core-25.79.jar"
    assets=ROOT/"modules/coronapoker-assets/target/coronapoker-assets-25.79.jar"
    subprocess.run([str(JDK/"bin/javac"),"--release","17","-cp",str(core),"-d",str(classes),str(PORT/"tools/DesktopPeerProbe.java")],check=True)
    deps=[core,assets,ROOT/".m2/repository/org/xerial/sqlite-jdbc/3.51.1.0/sqlite-jdbc-3.51.1.0.jar",
          ROOT/".m2/repository/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"]
    print(json.dumps({"desktopCoreJar":str(core),"sha256":hashlib.sha256(core.read_bytes()).hexdigest(),"version":"25.79","adapterClassesOnClasspath":False}),flush=True)
    subprocess.run([str(JDK/"bin/java"),"-cp",":".join(str(p) for p in [classes,*deps]),
                    "com.tonikelope.coronapoker.iosvalidation.DesktopPeerProbe",args.mode,args.address,str(args.port),
                    str(PORT/"build/desktop-peer/runs"/args.run_id),str(args.hands),str(args.seconds)],check=True)

if __name__=="__main__":main()
