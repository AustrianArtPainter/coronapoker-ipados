#include <jni.h>
#include "sqlite3.h"

/* NativeDB.c is compiled with JNI_OnLoad renamed to avoid other libraries. */
extern jint CP_SQLiteJNI_OnLoad(JavaVM *vm, void *reserved);

JNIEXPORT jboolean JNICALL Java_com_tonikelope_coronapoker_ios_NativeSQLite_initialize(JNIEnv *env, jclass type) {
    JavaVM *vm = NULL;
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK) return JNI_FALSE;
    jint result = CP_SQLiteJNI_OnLoad(vm, NULL);
    return result >= JNI_VERSION_1_2 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_tonikelope_coronapoker_ios_NativeSQLite_version(JNIEnv *env, jclass type) {
    return (*env)->NewStringUTF(env, sqlite3_libversion());
}
