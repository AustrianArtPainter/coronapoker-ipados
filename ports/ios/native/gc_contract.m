#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

extern void GC_gcollect(void);
static atomic_bool CPHeartbeatRun, CPHeartbeatExited;
static _Atomic uint64_t CPHeartbeatTicks;
static pthread_t CPHeartbeatThread;
static int CPHeartbeatStarted;
static jobject CPSentinel;
static JavaVM *CPVM;
static jclass CPProbeClass;
static jmethodID CPForeignCallback;
static pthread_t CPRawCoordinatorThread;
static int CPRawCoordinatorStarted;
static atomic_bool CPRawRun, CPRawExited;
static _Atomic uint64_t CPRawCreated, CPRawAttached, CPRawCallbacks, CPRawDetached, CPRawValidated, CPRawErrors;
static const char *CPTag = "CoronaPoker native-only JNI global reference sentinel v1";

static int CPAllowed(JNIEnv *env) {
    const char *mode = getenv("CP_IOS_GC_PROBE");
    if (mode && strcmp(mode, "1") == 0 && pthread_main_np()) return 1;
    jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (error) (*env)->ThrowNew(env, error, "GC contract JNI requires CP_IOS_GC_PROBE=1 on the native main thread");
    return 0;
}
static void CPThrow(JNIEnv *env, const char *message) {
    jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (error) (*env)->ThrowNew(env, error, message);
}
static uint64_t CPMonotonic(void) {
    struct timespec value;
    clock_gettime(CLOCK_MONOTONIC, &value);
    return (uint64_t)value.tv_sec * UINT64_C(1000000000) + (uint64_t)value.tv_nsec;
}
static jlong CPWord(int index) {
    return (jlong)(UINT64_C(0x6A09E667F3BCC909) ^ ((uint64_t)index * UINT64_C(0x100000001B3)));
}
static void *CPHeartbeat(void *unused) {
    // Deliberately no JNIEnv, Java callback, JNI attachment, heap pointer,
    // Foundation object, or GC registration in this plain native thread.
    const struct timespec delay = {0, 1000000};
    while (atomic_load_explicit(&CPHeartbeatRun, memory_order_acquire)) {
        atomic_fetch_add_explicit(&CPHeartbeatTicks, 1, memory_order_relaxed);
        nanosleep(&delay, NULL);
    }
    atomic_store_explicit(&CPHeartbeatExited, 1, memory_order_release);
    return NULL;
}
static jboolean CPValidateSentinel(JNIEnv *env);
static void *CPAttachedLeaf(void *index) {
    JNIEnv *env = NULL;
    if ((*CPVM)->AttachCurrentThread(CPVM, (void **)&env, NULL) != JNI_OK || !env) {
        atomic_fetch_add(&CPRawErrors, 1); return NULL;
    }
    atomic_fetch_add(&CPRawAttached, 1);
    if (CPValidateSentinel(env) && !(*env)->ExceptionCheck(env)) {
        atomic_fetch_add(&CPRawValidated, 1);
        (*env)->CallStaticVoidMethod(env, CPProbeClass, CPForeignCallback, (jint)(intptr_t)index);
        if (!(*env)->ExceptionCheck(env)) atomic_fetch_add(&CPRawCallbacks, 1);
        else { atomic_fetch_add(&CPRawErrors, 1); (*env)->ExceptionClear(env); }
    } else {
        atomic_fetch_add(&CPRawErrors, 1);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    if ((*CPVM)->DetachCurrentThread(CPVM) == JNI_OK) atomic_fetch_add(&CPRawDetached, 1);
    else atomic_fetch_add(&CPRawErrors, 1);
    return NULL;
}
static void *CPRawCoordinator(void *unused) {
    const struct timespec delay = {0, 30000000};
    uint64_t limit = CPMonotonic() + UINT64_C(27000000000);
    for (int i = 0; i < 256 && atomic_load(&CPRawRun) && CPMonotonic() < limit; i++) {
        pthread_t leaf;
        if (pthread_create(&leaf, NULL, CPAttachedLeaf, (void *)(intptr_t)i) != 0) { atomic_fetch_add(&CPRawErrors, 1); break; }
        atomic_fetch_add(&CPRawCreated, 1);
        if (pthread_join(leaf, NULL) != 0) { atomic_fetch_add(&CPRawErrors, 1); break; }
        if (atomic_load(&CPRawErrors) != 0) break;
        nanosleep(&delay, NULL);
    }
    atomic_store_explicit(&CPRawExited, 1, memory_order_release);
    return NULL;
}

JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSGCContractProbe_startNative(JNIEnv *env, jclass type) {
    if (!CPAllowed(env)) return;
    if (CPHeartbeatStarted || CPRawCoordinatorStarted || CPSentinel || CPProbeClass) { CPThrow(env, "GC contract native state already exists"); return; }
    if ((*env)->GetJavaVM(env, &CPVM) != JNI_OK) { CPThrow(env, "Unable to read the JavaVM invocation interface"); return; }
    CPForeignCallback = (*env)->GetStaticMethodID(env, type, "foreignThreadCallback", "(I)V");
    if (!CPForeignCallback || (*env)->ExceptionCheck(env)) return;
    if ((*env)->PushLocalFrame(env, 12) != JNI_OK) return;
    jclass object = (*env)->FindClass(env, "java/lang/Object");
    jobjectArray root = object ? (*env)->NewObjectArray(env, 3, object, NULL) : NULL;
    jobjectArray inner = object ? (*env)->NewObjectArray(env, 2, object, NULL) : NULL;
    jlongArray payload = (*env)->NewLongArray(env, 1024);
    jstring tag = (*env)->NewStringUTF(env, CPTag);
    if (root && inner && payload && tag && !(*env)->ExceptionCheck(env)) {
        jlong words[64];
        for (int block = 0; block < 16; block++) {
            for (int i = 0; i < 64; i++) words[i] = CPWord(block * 64 + i);
            (*env)->SetLongArrayRegion(env, payload, block * 64, 64, words);
        }
        (*env)->SetObjectArrayElement(env, root, 0, payload);
        (*env)->SetObjectArrayElement(env, root, 1, inner);
        (*env)->SetObjectArrayElement(env, root, 2, tag);
        (*env)->SetObjectArrayElement(env, inner, 0, root);
        (*env)->SetObjectArrayElement(env, inner, 1, payload);
        if (!(*env)->ExceptionCheck(env)) CPSentinel = (*env)->NewGlobalRef(env, root);
    }
    // Java never receives this graph. The global JNI reference is its only
    // intentional root between validations; all construction locals are gone.
    (*env)->PopLocalFrame(env, NULL);
    if ((*env)->ExceptionCheck(env)) return;
    if (!CPSentinel) { CPThrow(env, "Unable to construct the JNI sentinel"); return; }
    CPProbeClass = (*env)->NewGlobalRef(env, type);
    if (!CPProbeClass) { (*env)->DeleteGlobalRef(env, CPSentinel); CPSentinel = NULL; return; }
    atomic_store(&CPHeartbeatTicks, 0);
    atomic_store(&CPHeartbeatExited, 0);
    atomic_store(&CPHeartbeatRun, 1);
    int error = pthread_create(&CPHeartbeatThread, NULL, CPHeartbeat, NULL);
    if (error) {
        atomic_store(&CPHeartbeatRun, 0);
        (*env)->DeleteGlobalRef(env, CPSentinel); CPSentinel = NULL;
        (*env)->DeleteGlobalRef(env, CPProbeClass); CPProbeClass = NULL;
        CPThrow(env, "Unable to create the native heartbeat thread"); return;
    }
    CPHeartbeatStarted = 1;
    atomic_store(&CPRawCreated, 0); atomic_store(&CPRawAttached, 0); atomic_store(&CPRawCallbacks, 0);
    atomic_store(&CPRawDetached, 0); atomic_store(&CPRawValidated, 0); atomic_store(&CPRawErrors, 0);
    atomic_store(&CPRawRun, 1); atomic_store(&CPRawExited, 0);
    if (pthread_create(&CPRawCoordinatorThread, NULL, CPRawCoordinator, NULL) != 0) {
        atomic_store(&CPHeartbeatRun, 0); pthread_join(CPHeartbeatThread, NULL); CPHeartbeatStarted = 0;
        (*env)->DeleteGlobalRef(env, CPSentinel); CPSentinel = NULL;
        (*env)->DeleteGlobalRef(env, CPProbeClass); CPProbeClass = NULL;
        CPThrow(env, "Unable to create the native attach/detach coordinator"); return;
    }
    CPRawCoordinatorStarted = 1;
}
JNIEXPORT jlong JNICALL Java_com_tonikelope_coronapoker_ios_IOSGCContractProbe_heartbeat(JNIEnv *env, jclass type) {
    if (!CPAllowed(env)) return 0;
    return (jlong)atomic_load_explicit(&CPHeartbeatTicks, memory_order_relaxed);
}
JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSGCContractProbe_collectFullGC(JNIEnv *env, jclass type) {
    if (!CPAllowed(env)) return;
    GC_gcollect();
}
static jboolean CPValidateSentinel(JNIEnv *env) {
    if (!CPSentinel) return JNI_FALSE;
    if ((*env)->PushLocalFrame(env, 12) != JNI_OK) return JNI_FALSE;
    jboolean valid = JNI_FALSE;
    jobjectArray root = (jobjectArray)(*env)->NewLocalRef(env, CPSentinel);
    jobjectArray inner = root ? (jobjectArray)(*env)->GetObjectArrayElement(env, root, 1) : NULL;
    jlongArray payload = root ? (jlongArray)(*env)->GetObjectArrayElement(env, root, 0) : NULL;
    jstring tag = root ? (jstring)(*env)->GetObjectArrayElement(env, root, 2) : NULL;
    if (root && inner && payload && tag && !(*env)->ExceptionCheck(env)
        && (*env)->GetArrayLength(env, root) == 3 && (*env)->GetArrayLength(env, inner) == 2
        && (*env)->GetArrayLength(env, payload) == 1024) {
        jobject cycle = (*env)->GetObjectArrayElement(env, inner, 0);
        jobject alias = (*env)->GetObjectArrayElement(env, inner, 1);
        const char *text = (*env)->GetStringUTFChars(env, tag, NULL);
        valid = text && strcmp(text, CPTag) == 0 && (*env)->IsSameObject(env, cycle, root)
                && (*env)->IsSameObject(env, alias, payload);
        if (text) (*env)->ReleaseStringUTFChars(env, tag, text);
        jlong words[64];
        for (int block = 0; block < 16 && valid; block++) {
            (*env)->GetLongArrayRegion(env, payload, block * 64, 64, words);
            if ((*env)->ExceptionCheck(env)) { valid = JNI_FALSE; break; }
            for (int i = 0; i < 64; i++) if (words[i] != CPWord(block * 64 + i)) { valid = JNI_FALSE; break; }
        }
    }
    (*env)->PopLocalFrame(env, NULL);
    return valid;
}
JNIEXPORT jboolean JNICALL Java_com_tonikelope_coronapoker_ios_IOSGCContractProbe_validateNativeSentinel(JNIEnv *env, jclass type) {
    if (!CPAllowed(env)) return JNI_FALSE;
    return CPValidateSentinel(env);
}
JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSGCContractProbe_nativeCounters(JNIEnv *env, jclass type, jlongArray output) {
    if (!CPAllowed(env)) return;
    if (!output || (*env)->GetArrayLength(env, output) != 8) { CPThrow(env, "Native GC contract counters require eight slots"); return; }
    jlong values[8] = {atomic_load(&CPRawCreated), atomic_load(&CPRawAttached), atomic_load(&CPRawCallbacks),
        atomic_load(&CPRawDetached), atomic_load(&CPRawValidated), atomic_load(&CPRawErrors),
        atomic_load(&CPRawExited), atomic_load(&CPHeartbeatExited)};
    (*env)->SetLongArrayRegion(env, output, 0, 8, values);
}
JNIEXPORT jboolean JNICALL Java_com_tonikelope_coronapoker_ios_IOSGCContractProbe_stopNative(JNIEnv *env, jclass type) {
    if (!CPAllowed(env)) return JNI_FALSE;
    atomic_store_explicit(&CPHeartbeatRun, 0, memory_order_release);
    atomic_store_explicit(&CPRawRun, 0, memory_order_release);
    uint64_t limit = CPMonotonic() + UINT64_C(2000000000);
    const struct timespec delay = {0, 1000000};
    while (((CPHeartbeatStarted && !atomic_load_explicit(&CPHeartbeatExited, memory_order_acquire))
            || (CPRawCoordinatorStarted && !atomic_load_explicit(&CPRawExited, memory_order_acquire))) && CPMonotonic() < limit)
        nanosleep(&delay, NULL);
    if (CPHeartbeatStarted && atomic_load_explicit(&CPHeartbeatExited, memory_order_acquire)) {
        if (pthread_join(CPHeartbeatThread, NULL) == 0) CPHeartbeatStarted = 0;
    }
    if (CPRawCoordinatorStarted && atomic_load_explicit(&CPRawExited, memory_order_acquire)) {
        if (pthread_join(CPRawCoordinatorThread, NULL) == 0) CPRawCoordinatorStarted = 0;
    }
    if (!CPRawCoordinatorStarted) {
        if (CPSentinel) { (*env)->DeleteGlobalRef(env, CPSentinel); CPSentinel = NULL; }
        if (CPProbeClass) { (*env)->DeleteGlobalRef(env, CPProbeClass); CPProbeClass = NULL; }
    }
    return CPHeartbeatStarted || CPRawCoordinatorStarted ? JNI_FALSE : JNI_TRUE;
}
