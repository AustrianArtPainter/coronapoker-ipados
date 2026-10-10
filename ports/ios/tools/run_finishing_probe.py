#!/usr/bin/env python3
"""Actual native Pixmap lifetime and exact font vertices without a GPU/audio device."""
import subprocess
from pathlib import Path
from toolchain import jdk_home

PORT=Path(__file__).resolve().parents[1]
ROOT=PORT.parents[1]
output=PORT/'build/finishing-tests';output.mkdir(parents=True,exist_ok=True)
runtime=PORT/'.cache/toolchains/robovm-3.0.0-SNAPSHOT/lib'
cp=':'.join(map(str,[PORT/'build/game/classes',
    ROOT/'.m2/repository/com/badlogicgames/gdx/gdx/1.14.2/gdx-1.14.2.jar',
    ROOT/'.m2/repository/com/badlogicgames/gdx/gdx-platform/1.14.2/gdx-platform-1.14.2-natives-desktop.jar',
    ROOT/'.m2/repository/com/badlogicgames/gdx/gdx-jnigen-loader/2.5.2/gdx-jnigen-loader-2.5.2.jar',
    *(runtime/name for name in ['robovm-rt.jar','robovm-bro-bridge.jar','robovm-objc.jar','robovm-cocoatouch.jar'])]))
java=jdk_home()/'bin'
subprocess.run([str(java/'javac'),'--release','17','-cp',cp,'-d',str(output),str(PORT/'tools/FinishingPreloadProbe.java')],check=True)
subprocess.run([str(java/'java'),'-Xmx512m','-cp',str(output)+':'+cp,
    'com.tonikelope.coronapoker.gdx.FinishingPreloadProbe',str(ROOT/'src/main/resources')],check=True)
