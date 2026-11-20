#import <AVFoundation/AVFoundation.h>
#import <Foundation/Foundation.h>
#import <mach/mach_time.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <unistd.h>

// Only the explicit Java->JNI render hook samples. No native timer, Java
// callback, Java heap/global reference, thread attachment, or audio setter.
typedef struct {
    uint64_t before, after;
    double position, device;
    float volume;
    uint32_t playing;
} CPClockSample;
typedef struct {
    uint64_t mach, delivered;
    uint64_t handle;
    double value;
    int kind, sourceMain;
} CPClockEvent;
@interface CPMusicClockState : NSObject {
@public
    AVAudioPlayer *player;
    NSString *directory, *runID, *trigger;
    NSMutableArray *observers;
    NSDictionary *readyAnchor, *sessionReady, *sessionStart, *sessionEnd, *finishAnchor;
    CPClockSample *samples;
    CPClockEvent *events;
    NSUInteger count, capacity, eventCount, eventCapacity;
    uint64_t handle, registeredMach, startMach, endMach, nextPollMach;
    mach_timebase_info_data_t timebase;
    double seconds;
    long long volumeAttempts, volumeChanges;
    BOOL started, finished, readyWritten, overflow;
    NSString *error;
}
@end
@implementation CPMusicClockState
- (void)dealloc { free(samples); free(events); }
@end
static CPMusicClockState *CPClock;

static void CPClockThrow(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message);
}
static BOOL CPClockAllowed(JNIEnv *env) {
    const char *value = getenv("CP_IOS_AUDIO_CLOCK_SECONDS");
    if (value && *value && NSThread.isMainThread) return YES;
    CPClockThrow(env, "Audio clock JNI requires its explicit diagnostic flag on the main thread");
    return NO;
}
static NSString *CPClockString(JNIEnv *env, jstring text) {
    if (!text) return nil;
    const jchar *chars = (*env)->GetStringChars(env, text, NULL);
    if (!chars) return nil;
    NSString *value = [[NSString alloc] initWithCharacters:(const unichar *)chars length:(NSUInteger)(*env)->GetStringLength(env, text)];
    (*env)->ReleaseStringChars(env, text, chars);
    return value;
}
static double CPClockElapsed(CPMusicClockState *s, uint64_t from, uint64_t to) {
    return (double)(to - from) * s->timebase.numer / s->timebase.denom / 1e9;
}
static NSDictionary *CPClockAnchor(void) {
    uint64_t before = mach_absolute_time(); struct timeval wall; gettimeofday(&wall, NULL); uint64_t after = mach_absolute_time();
    return @{ @"machBefore": @(before), @"unixSeconds": @((double)wall.tv_sec + wall.tv_usec / 1e6), @"machAfter": @(after) };
}
static NSDictionary *CPClockSession(void) {
    AVAudioSession *session = AVAudioSession.sharedInstance;
    NSMutableArray *outputs = [NSMutableArray array];
    for (AVAudioSessionPortDescription *port in session.currentRoute.outputs)
        [outputs addObject:@{ @"type": port.portType ?: @"", @"name": port.portName ?: @"", @"uid": port.UID ?: @"" }];
    return @{ @"category": session.category ?: @"", @"mode": session.mode ?: @"",
              @"sampleRate": @(session.sampleRate), @"ioBufferSeconds": @(session.IOBufferDuration),
              @"preferredIOBufferSeconds": @(session.preferredIOBufferDuration), @"outputVolume": @(session.outputVolume),
              @"outputLatencySeconds": @(session.outputLatency), @"outputs": outputs };
}
static CPClockSample CPClockRead(CPMusicClockState *s) {
    CPClockSample value = {0}; value.before = mach_absolute_time();
    value.position = s->player.currentTime; value.device = s->player.deviceCurrentTime;
    value.playing = s->player.playing; value.volume = s->player.volume;
    value.after = mach_absolute_time(); return value;
}
static void CPClockAppend(CPMusicClockState *s, CPClockEvent event) {
    if (s->finished) return;
    event.delivered = mach_absolute_time();
    if (s->eventCount < s->eventCapacity) s->events[s->eventCount++] = event;
    else { s->overflow = YES; s->error = @"Sparse native event storage overflowed"; }
}
static BOOL CPClockWriteJSON(NSDictionary *data, NSString *path) {
    NSError *error = nil;
    NSData *encoded = [NSJSONSerialization dataWithJSONObject:data options:NSJSONWritingPrettyPrinted error:&error];
    return encoded && [encoded writeToFile:path options:NSDataWritingAtomic error:&error];
}
static NSDictionary *CPClockBase(CPMusicClockState *s) {
    return @{ @"schema": @1, @"kind": @"native-music-clock-v1", @"pid": @(getpid()), @"runID": s->runID,
              @"selectedPlayerIdentity": @(s->handle), @"asset": @"sounds/misc/background_music.mp3",
              @"backend": @"legacy", @"requestedSeconds": @(s->seconds), @"sampleCapacity": @(s->capacity),
              @"sampleRecordBytes": @(sizeof(CPClockSample)), @"eventCapacity": @(s->eventCapacity),
              @"machTimebase": @{ @"numer": @(s->timebase.numer), @"denom": @(s->timebase.denom), @"clock": @"mach_absolute_time" },
              @"sampling": @"Render-driven Java->JNI, with the existing IOSMusic monitor held; not a 10ms timer or real-time audio callback. All scalar properties are read per observed render hook.",
              @"scope": @"Diagnostic native player/output-device clock observation only. currentTime/deviceCurrentTime are not promised sample-accurate PCM clocks. Smooth clocks do not exclude decoder/IO loss, and capture-tap silence does not establish App speaker silence.",
              @"audioPolicyModified": @NO, @"nativeTimerCreated": @NO, @"nativeJavaCallbackCreated": @NO,
              @"nativeStoresJavaReferences": @NO, @"normalSceneAcceptance": @NO };
}
static void CPClockFinish(JNIEnv *env, CPMusicClockState *s, jlongArray counters, NSString *failure) {
    if (s->finished) return;
    s->endMach = mach_absolute_time(); s->finished = YES;
    if (failure && !s->error) s->error = failure;
    // This is still inside a legal Java->JNI invocation with its live array
    // argument and IOSMusic monitor. No Java references escape to the writer.
    if (counters && (*env)->GetArrayLength(env, counters) == 2) {
        jlong copy[2]; (*env)->GetLongArrayRegion(env, counters, 0, 2, copy);
        if (!(*env)->ExceptionCheck(env)) { s->volumeAttempts = copy[0]; s->volumeChanges = copy[1]; }
        else s->error = @"Unable to copy final Java volume counters";
    } else s->error = @"Missing final Java volume counters";
    for (id observer in s->observers) [NSNotificationCenter.defaultCenter removeObserver:observer];
    [s->observers removeAllObjects];
    s->finishAnchor = CPClockAnchor(); s->sessionEnd = CPClockSession();
    s->player = nil; // No further player access, including in the utility writer.
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
        @autoreleasepool {
            BOOL filesOK = YES;
            NSString *samplePath = [s->directory stringByAppendingPathComponent:@"samples.csv"];
            FILE *sampleFile = fopen(samplePath.fileSystemRepresentation, "w");
            if (!sampleFile) filesOK = NO;
            else {
                fprintf(sampleFile, "machBefore,machAfter,currentTimeSeconds,deviceTimeSeconds,playing,volume\n");
                for (NSUInteger i = 0; i < s->count; i++) {
                    CPClockSample v = s->samples[i];
                    if (fprintf(sampleFile, "%llu,%llu,%.12f,%.12f,%u,%.9g\n", (unsigned long long)v.before, (unsigned long long)v.after, v.position, v.device, v.playing, v.volume) < 0) filesOK = NO;
                }
                if (fclose(sampleFile) != 0) filesOK = NO;
            }
            FILE *eventFile = fopen([[s->directory stringByAppendingPathComponent:@"events.csv"] fileSystemRepresentation], "w");
            if (!eventFile) filesOK = NO;
            else {
                fprintf(eventFile, "observedMach,deliveredMainMach,playerIdentity,kind,value,sourceMainThread\n");
                for (NSUInteger i = 0; i < s->eventCount; i++) {
                    CPClockEvent v = s->events[i];
                    if (fprintf(eventFile, "%llu,%llu,%llu,%d,%.12f,%d\n", (unsigned long long)v.mach, (unsigned long long)v.delivered, (unsigned long long)v.handle, v.kind, v.value, v.sourceMain) < 0) filesOK = NO;
                }
                if (fclose(eventFile) != 0) filesOK = NO;
            }
            NSMutableDictionary *report = [CPClockBase(s) mutableCopy];
            report[@"completed"] = @YES; report[@"validDiagnostic"] = @((BOOL)(filesOK && s->started && !s->error && !s->overflow));
            report[@"error"] = s->error ?: (filesOK ? (id)NSNull.null : @"CSV write failed");
            report[@"samples"] = @(s->count); report[@"events"] = @(s->eventCount); report[@"overflow"] = @(s->overflow);
            report[@"startedMach"] = @(s->startMach); report[@"stoppedMach"] = @(s->endMach);
            report[@"observedSeconds"] = s->started ? @(CPClockElapsed(s, s->startMach, s->endMach)) : @0;
            report[@"readyWallAnchor"] = s->readyAnchor; report[@"finishWallAnchor"] = s->finishAnchor;
            report[@"sessionAtReady"] = s->sessionReady; report[@"sessionAtStart"] = s->sessionStart ?: (id)NSNull.null; report[@"sessionAtEnd"] = s->sessionEnd;
            report[@"volumeSetterAttemptsThroughFinalSnapshot"] = @(s->volumeAttempts); report[@"differentVolumeValuesWritten"] = @(s->volumeChanges);
            report[@"volumeCountScope"] = @"All successful setters on this player since diagnostic registration through the terminal JNI snapshot; includes pre-trigger preparation. Same-value writes still reach AVAudioPlayer, but add no per-frame diagnostic JNI.";
            report[@"eventCodes"] = @{ @"1": @"play succeeded", @"2": @"pause", @"3": @"stop and rewind", @"4": @"setPosition requested seconds", @"5": @"different volume value written", @"6": @"loop requested", @"7": @"pan requested", @"8": @"completion", @"9": @"decode error code", @"10": @"dispose", @"11": @"AVAudioSession activation succeeded", @"20": @"route-change reason", @"21": @"interruption type", @"22": @"media services lost", @"23": @"media services reset", @"30": @"duplicate/recreated background player rejected" };
            report[@"eventTiming"] = @"Java control events are stamped after their original operation; native notifications are observed on main queue. deliveredMainMach exposes off-main delivery delay. Neither marks exact audio engine transition time.";
            report[@"writerScope"] = @"CSV/terminal JSON written on native utility queue only after sampling stopped and Java counters were copied; writer has no JNIEnv, Java references or callbacks.";
            CPClockWriteJSON(report, [s->directory stringByAppendingPathComponent:@"report.json"]);
        }
    });
}
JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSMusicClock_registerNative(JNIEnv *env, jclass type, jlong identity, jstring directory, jstring runID, jint seconds) {
    if (!CPClockAllowed(env)) return;
    if (CPClock || !identity || seconds < 10 || seconds > 240) { CPClockThrow(env, "Audio clock requires one unique player and a 10..240 second window"); return; }
    CPMusicClockState *s = [CPMusicClockState new];
    s->player = (__bridge AVAudioPlayer *)(void *)(intptr_t)identity; s->handle = (uint64_t)identity;
    s->directory = CPClockString(env, directory); s->runID = CPClockString(env, runID); s->seconds = seconds;
    if (!s->directory || !s->runID || (*env)->ExceptionCheck(env)) return;
    s->capacity = (NSUInteger)seconds * 240 + 64; // Bounded even on a 120Hz render surface.
    s->eventCapacity = 4096; s->samples = calloc(s->capacity, sizeof(CPClockSample)); s->events = calloc(s->eventCapacity, sizeof(CPClockEvent));
    if (!s->samples || !s->events) { CPClockThrow(env, "Unable to allocate bounded native clock storage"); return; }
    NSError *error = nil;
    if (![NSFileManager.defaultManager createDirectoryAtPath:s->directory withIntermediateDirectories:YES attributes:nil error:&error]) { CPClockThrow(env, "Unable to create private clock diagnostic directory"); return; }
    s->trigger = [s->directory stringByAppendingPathComponent:@"start"];
    unlink(s->trigger.fileSystemRepresentation); // Fresh run: a preexisting trigger never arms a new player.
    mach_timebase_info(&s->timebase); s->registeredMach = mach_absolute_time(); s->readyAnchor = CPClockAnchor(); s->sessionReady = CPClockSession();
    s->observers = [NSMutableArray array]; CPClock = s;
    NSArray *names = @[AVAudioSessionRouteChangeNotification, AVAudioSessionInterruptionNotification, AVAudioSessionMediaServicesWereLostNotification, AVAudioSessionMediaServicesWereResetNotification];
    for (NSUInteger i = 0; i < names.count; i++) {
        id token = [NSNotificationCenter.defaultCenter addObserverForName:names[i] object:nil queue:NSOperationQueue.mainQueue usingBlock:^(NSNotification *notification) {
            CPClockEvent e = { .mach = mach_absolute_time(), .handle = s->handle, .kind = 20 + (int)i, .sourceMain = 1 };
            if (i == 0) e.value = [notification.userInfo[AVAudioSessionRouteChangeReasonKey] doubleValue];
            if (i == 1) e.value = [notification.userInfo[AVAudioSessionInterruptionTypeKey] doubleValue];
            CPClockAppend(s, e);
        }];
        [s->observers addObject:token];
    }
    CPClockSample ready = CPClockRead(s);
    NSMutableDictionary *report = [CPClockBase(s) mutableCopy];
    report[@"ready"] = @YES; report[@"waitingForRunSpecificTrigger"] = @YES; report[@"triggerPath"] = s->trigger;
    report[@"waitTimeoutSeconds"] = @900; report[@"readyWallAnchor"] = s->readyAnchor; report[@"sessionAtReady"] = s->sessionReady;
    report[@"readySample"] = @{ @"machBefore": @(ready.before), @"machAfter": @(ready.after), @"currentTime": @(ready.position), @"deviceTime": @(ready.device), @"playing": @(ready.playing), @"volume": @(ready.volume) };
    s->readyWritten = CPClockWriteJSON(report, [s->directory stringByAppendingPathComponent:@"ready.json"]);
    if (!s->readyWritten) { s->error = @"Unable to write ready metadata"; CPClockThrow(env, "Unable to write clock readiness metadata"); }
}
JNIEXPORT jboolean JNICALL Java_com_tonikelope_coronapoker_ios_IOSMusicClock_sampleNative(JNIEnv *env, jclass type, jlong identity, jlongArray counters) {
    if (!CPClockAllowed(env)) return JNI_FALSE;
    CPMusicClockState *s = CPClock; if (!s || s->finished) return JNI_FALSE;
    if ((uint64_t)identity != s->handle) { CPClockFinish(env, s, counters, @"Selected background player identity changed"); return JNI_FALSE; }
    if (s->error || s->overflow) { CPClockFinish(env, s, counters, nil); return JNI_FALSE; }
    uint64_t now = mach_absolute_time();
    if (!s->started) {
        if (CPClockElapsed(s, s->registeredMach, now) >= 900) { CPClockFinish(env, s, counters, @"Run-specific clock trigger timed out"); return JNI_FALSE; }
        if (now < s->nextPollMach) return JNI_TRUE;
        s->nextPollMach = now + (uint64_t)(250000000.0 * s->timebase.denom / s->timebase.numer);
        if (access(s->trigger.fileSystemRepresentation, F_OK) != 0) return JNI_TRUE;
        s->sessionStart = CPClockSession(); // Read once before the measured window.
        s->startMach = mach_absolute_time(); s->started = YES;
    }
    if (s->count >= s->capacity) { s->overflow = YES; CPClockFinish(env, s, counters, @"Native clock sample capacity exceeded"); return JNI_FALSE; }
    CPClockSample value = CPClockRead(s); s->samples[s->count++] = value;
    if (CPClockElapsed(s, s->startMach, value.after) >= s->seconds) CPClockFinish(env, s, counters, nil);
    return s->finished ? JNI_FALSE : JNI_TRUE;
}
JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSMusicClock_eventNative(JNIEnv *env, jclass type, jlong identity, jint kind, jdouble value) {
    CPMusicClockState *s = CPClock; if (!s) return;
    CPClockEvent event = { .mach = mach_absolute_time(), .handle = (uint64_t)identity, .kind = kind, .value = value, .sourceMain = NSThread.isMainThread };
    void (^record)(void) = ^{
        if (s->finished) return;
        if (kind == 30 || (identity && (uint64_t)identity != s->handle)) s->error = @"Duplicate/recreated background player rejected";
        CPClockAppend(s, event);
    };
    if (NSThread.isMainThread) record(); else dispatch_async(dispatch_get_main_queue(), record);
}
JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSMusicClock_detachNative(JNIEnv *env, jclass type, jlong identity, jlongArray counters) {
    const char *flag = getenv("CP_IOS_AUDIO_CLOCK_SECONDS");
    if (!flag || !*flag) { CPClockThrow(env, "Audio clock detach requires its explicit flag"); return; }
    CPMusicClockState *s = CPClock; if (!s) return;
    CPClockEvent event = { .mach = mach_absolute_time(), .handle = (uint64_t)identity, .kind = 10, .sourceMain = NSThread.isMainThread };
    if (!NSThread.isMainThread) {
        // No JNIEnv/Java argument escapes. The next legal main render JNI
        // copies counters and finalizes, even though Java disposed its wrapper.
        // This state keeps the native player alive until that finalization.
        dispatch_async(dispatch_get_main_queue(), ^{
            if (s->finished) return;
            CPClockAppend(s, event); s->error = @"Selected background player disposed off main thread before completion";
        });
        return;
    }
    if (s->finished) return;
    CPClockAppend(s, event);
    CPClockFinish(env, s, counters, @"Selected background player disposed before the clock window finished");
}
