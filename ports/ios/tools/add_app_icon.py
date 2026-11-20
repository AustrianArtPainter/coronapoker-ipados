#!/usr/bin/env python3
"""Compile the original desktop logo into the native iPhone/iPad icon catalog."""
import argparse
import json
import plistlib
import subprocess
from pathlib import Path

PORT=Path(__file__).resolve().parents[1]
ROOT=PORT.parents[1]

def add_icon(app,platform="iphoneos"):
    app=app.resolve()
    catalog=PORT/"build/app-icon/Assets.xcassets"
    icon=catalog/"AppIcon.appiconset";icon.mkdir(parents=True,exist_ok=True)
    cache=PORT/".cache/swift-module-cache";cache.mkdir(parents=True,exist_ok=True)
    subprocess.run(["xcrun","swift","-module-cache-path",str(cache),str(PORT/"tools/derive_app_icon.swift"),
        str(ROOT/"src/main/resources/images/coronapoker_logo_big.png"),str(icon/"CoronaPoker.png")],check=True)
    (icon/"Contents.json").write_text(json.dumps({"images":[{"idiom":"universal","platform":"ios",
        "size":"1024x1024","filename":"CoronaPoker.png"}],"info":{"author":"xcode","version":1}}))
    partial=PORT/"build/app-icon/partial-info.plist"
    subprocess.run(["xcrun","actool","--compile",str(app),"--platform",platform,
        "--minimum-deployment-target","18.0","--app-icon","AppIcon","--output-partial-info-plist",str(partial),
        "--target-device","iphone","--target-device","ipad",str(catalog)],check=True)
    info=plistlib.loads((app/"Info.plist").read_bytes())
    info.update(plistlib.loads(partial.read_bytes()))
    (app/"Info.plist").write_bytes(plistlib.dumps(info))

if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("app",type=Path)
    add_icon(parser.parse_args().app)
