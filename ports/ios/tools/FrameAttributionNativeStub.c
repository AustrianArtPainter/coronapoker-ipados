/* Host-only controlled JNI clock. Never linked by build_game.py. */
#include <jni.h>
static jlong reads, cpu_value, resolution_value = 1;
JNIEXPORT jlong JNICALL Java_com_tonikelope_coronapoker_ios_IOSFrameAttribution_cpuNanosNative(JNIEnv *e,jclass c) { reads++; return cpu_value; }
JNIEXPORT jlong JNICALL Java_com_tonikelope_coronapoker_ios_IOSFrameAttribution_cpuResolutionNanosNative(JNIEnv *e,jclass c) { reads++; return resolution_value; }
JNIEXPORT jlong JNICALL Java_com_tonikelope_coronapoker_ios_IOSFrameAttributionProbe_stubCalls(JNIEnv *e,jclass c) { return reads; }
JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSFrameAttributionProbe_stubCpu(JNIEnv *e,jclass c,jlong value) { cpu_value=value; }
JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSFrameAttributionProbe_stubResolution(JNIEnv *e,jclass c,jlong value) { resolution_value=value; }
