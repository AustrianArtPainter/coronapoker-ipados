#!/usr/bin/env python3
"""Exercise the actual primitive attribution recorder; no App, simulator or native runtime."""
import ast
import json
import os
from pathlib import Path
import subprocess
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
OUT = PORT / 'build/frame-attribution-tests'
OUT.mkdir(parents=True, exist_ok=True)
CLASSES = OUT / 'classes'
CLASSES.mkdir(exist_ok=True)
JAVA = jdk_home() / 'bin'
subprocess.run([str(JAVA/'javac'), '--release', '8', '-d', str(CLASSES),
    str(PORT/'shared-adapters/src/com/tonikelope/coronapoker/ios/IOSFrameAttribution.java'),
    str(PORT/'tools/IOSFrameAttributionProbe.java')], check=True)
LIB = OUT / 'libframe-attribution-stub.dylib'
subprocess.run(['xcrun','clang','-O2','-dynamiclib','-I',str(jdk_home()/'include'),
    '-I',str(jdk_home()/'include/darwin'),str(PORT/'tools/FrameAttributionNativeStub.c'),'-o',str(LIB)], check=True)
checks = 0
reports = {}
for mode in ('disabled','pair','resume','discard','failures','overflow','stage-errors','split-stages','stage-context','native-valid-zero','native-clock-failure','native-resolution-failure','native-owner-failure'):
    env = {k:v for k,v in os.environ.items() if not k.startswith('CP_IOS_')}
    if mode != 'disabled':env.update(CP_IOS_SCENE_EVIDENCE='1',CP_IOS_FRAME_ATTRIBUTION='1')
    c = subprocess.run([str(JAVA/'java'),'-cp',str(CLASSES),'com.tonikelope.coronapoker.ios.IOSFrameAttributionProbe',mode,str(LIB)],env=env,text=True,capture_output=True,check=True)
    d = json.loads(c.stdout);reports[mode]=d
    (OUT/(mode+'.json')).write_text(json.dumps(d,indent=2)+'\n')
    if mode == 'disabled':
        assert d == {'enabled':False,'nativeClockCalls':0,'allStageArraysNull':True};checks+=3;continue
    assert d['enabled'] is True and d['normalAcceptance'] is False and d['frozen'] is True
    assert d['nativeClockCalls']==(4 if mode.startswith('native-') else 0) and d['storagePrimitiveBytes'] == 113280
    assert d['stageCount']==20 and d['kind']=='bounded-main-thread-attribution-v2'
    assert d['thresholdNs']==25000000
    for e in d['events']:
        assert len(e['stages'])==20
        assert sum(s['wallNs'] or 0 for s in e['stages'])==e['renderEndElapsedNs']-e['renderStartElapsedNs'] or mode=='stage-errors'
        for j,s in enumerate(e['stages']):
            assert (s['wallNs'] is not None)==bool(e['seenMask'] & 1<<j and not e['invalidWallMask'] & 1<<j)
            if not e['seenMask'] & 1<<j or e['invalidCpuMask'] & 1<<j:assert s['cpuNs'] is None
        if e['renderCpuNs'] is not None and not e['invalidCpuMask']:
            assert sum(s['cpuNs'] or 0 for s in e['stages'])==e['renderCpuNs']
        checks+=20
    checks+=6
p=reports['pair'];e=p['events'];assert [r['renderSampleIndex'] for r in e]==[0,1,3]
assert [r['intervalSampleIndex'] for r in e]==[1,2,None]
assert [r['renderCpuNs'] for r in e]==[10000000,1000000,1000000]
assert [r['intervalCpuNs'] for r in e]==[15000000,5000000,None]
assert e[0]['stages'][2]['wallNs']==2000000 and e[0]['stages'][2]['cpuNs']==1000000
assert e[0]['stages'][6]['wallNs'] is None and p['retainedFrames']==4
assert p['renderWallTotalNs']==64000000 and p['validRenderCpuTotalNs']==13000000
assert p['stageErrors']==0;checks+=12
r=reports['resume'];assert r['suspendResets']==1 and [e['intervalSampleIndex'] for e in r['events']]==[None,2]
assert r['events'][1]['intervalCpuNs']==3000000;checks+=3
assert reports['discard']['retainedFrames']==1 and reports['discard']['stageErrors']==0;checks+=2
assert all(e['renderCpuNs'] is None for e in reports['failures']['events']);assert reports['failures']['clockAnomalies']>=2;checks+=2
assert reports['overflow']['stored']==256 and reports['overflow']['dropped']==4;checks+=2
assert reports['stage-errors']['stageErrors']>=2;checks+=1
split=reports['split-stages'];event=split['events'][0]
assert split['stored']==1 and split['stageErrors']==0 and event['seenMask']==983297
assert event['renderEndElapsedNs']-event['renderStartElapsedNs']==30000000 and event['renderCpuNs']==15000000
for at,wall,cpu in ((0,1000000,500000),(8,8000000,4000000),(16,2000000,1000000),
                    (17,5000000,2500000),(18,5000000,2500000),(19,9000000,4500000)):
    assert event['stages'][at]['wallNs']==wall and event['stages'][at]['cpuNs']==cpu
    checks+=2
context=reports['stage-context'];assert context['retainedFrames']==context['stored']==context['nativeClockCalls']==context['stageErrors']==0
assert context['suspendResets']==1;checks+=5
assert reports['native-valid-zero']['validCpuFrames']==1 and reports['native-valid-zero']['validRenderCpuTotalNs']==0;checks+=2
for mode in ('native-clock-failure','native-resolution-failure','native-owner-failure'):
    assert reports[mode]['validCpuFrames']==0;checks+=1
assert reports['native-clock-failure']['clockFailures']==3;checks+=1
assert reports['native-owner-failure']['threadOwnerFailures']==3;checks+=1
assert reports['native-resolution-failure']['resolutionNs'] is None;checks+=1
result={'passed':True,'checks':checks,'modes':list(reports),'javaClassMajor':int.from_bytes((CLASSES/'com/tonikelope/coronapoker/ios/IOSFrameAttribution.class').read_bytes()[6:8],'big'),'nativeRuntimeExecuted':'controlled host JNI stub only; real SDK JNI separately dual-arch compiled','scope':'Actual bounded recorder seams and genuine disabled/frozen public paths; host JNI stub counts actual getter invocations, including failure and valid zero paths.'}
assert result['javaClassMajor']==52
# Reuse the existing minimal runtime stubs, without executing its runner on import.
stub_tree = ast.parse((PORT/'tools/run_frame_evidence_probe.py').read_text())
stubs = next(ast.literal_eval(n.value) for n in stub_tree.body
             if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='STUBS' for t in n.targets))
paths=[]
for relative, content in stubs.items():
    if relative == 'org/robovm/rt/GC.java':
        content = content.replace(' public static long getCount()', ' public static int bytesStage=-1,countStage=-1;\n public static long getCount()')
        content = content.replace('getCount(){calls.incrementAndGet();', 'getCount(){countStage=com.tonikelope.coronapoker.ios.IOSFrameAttribution.currentStage();calls.incrementAndGet();')
        content = content.replace('getTotalBytes(){calls.incrementAndGet();', 'getTotalBytes(){bytesStage=com.tonikelope.coronapoker.ios.IOSFrameAttribution.currentStage();calls.incrementAndGet();')
    path=OUT/'integration-stubs'/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content);paths.append(path)
subprocess.run([str(JAVA/'javac'),'--release','17','-cp',str(CLASSES),'-d',str(CLASSES),*map(str,paths),
    str(PORT/'shared-adapters/src/com/tonikelope/coronapoker/ios/BackgroundProjection.java'),
    str(PORT/'shared-adapters/src/com/tonikelope/coronapoker/gdx/IOSFrameEvidence.java'),
    str(PORT/'tools/FrameAttributionIntegrationProbe.java')],check=True)
env={k:v for k,v in os.environ.items() if not k.startswith('CP_IOS_')}
env.update(CP_IOS_SCENE_EVIDENCE='1',CP_IOS_FRAME_ATTRIBUTION='1',CP_IOS_EVIDENCE_SECONDS='10',CP_IOS_EVIDENCE_START_PHASE='menu',CP_IOS_RUN_ID='frame-attribution-integration')
c=subprocess.run([str(JAVA/'java'),'-cp',str(CLASSES),'com.tonikelope.coronapoker.gdx.FrameAttributionIntegrationProbe'],env=env,text=True,capture_output=True,check=True)
i=json.loads(c.stdout);(OUT/'integration.json').write_text(json.dumps(i,indent=2)+'\n')
assert i['originMonotonicNanos']==7000000000 and i['completionReason']=='duration'
assert i['suspendCount']==1 and i['sampledFrames']==5
assert i['samples'][0]['intervalNs'] is None and i['samples'][2]['intervalNs'] is None
assert i['samples'][2]['flags'] & 16
assert i['diagnosticConfig']['frameAttribution']=='1'
a=i['frameAttribution'];assert a['enabled'] is True and a['retainedFrames']==5 and a['stageErrors']==0 and a['nativeClockCalls']==0
assert a['frozen'] is True and a['dropped']==0
for event in a['events']:
    at=event['renderSampleIndex'];raw=i['samples'][at]
    assert event['renderEndElapsedNs']-event['renderStartElapsedNs']==raw['currentRenderNs']
    assert sum(stage['wallNs'] or 0 for stage in event['stages'])==raw['currentRenderNs']
    following=event['intervalSampleIndex']
    if following is not None:
        assert following==at+1 and i['samples'][following]['previousRenderNs']==raw['currentRenderNs']
        assert event['intervalNs']==i['samples'][following]['intervalNs']
assert [(e['renderSampleIndex'],e['intervalSampleIndex']) for e in a['events']]==[(0,1),(2,3),(3,4),(4,None)]
result['samplerDeferredResumeIntegration']=True
result['checks']+=25
public_begin=subprocess.run([str(JAVA/'java'),'-cp',str(CLASSES),'com.tonikelope.coronapoker.gdx.FrameAttributionIntegrationProbe',
                             'public-begin',str(LIB)],env=env,text=True,capture_output=True,check=True)
b=json.loads(public_begin.stdout);(OUT/'public-begin.json').write_text(json.dumps(b,indent=2)+'\n')
assert b['stageCount']==20 and b['retainedFrames']==0 and b['stageErrors']==0
assert b['nativeClockCalls']==5 and b['clockFailures']==0 and b['threadOwnerFailures']==0
result['actualPublicBeginCounterStage']=True
result['checks']+=6
(OUT/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
