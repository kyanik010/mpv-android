#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <locale.h>
#include <atomic>

#include <mpv/client.h>

#include <pthread.h>

extern "C" {
    #include <libavcodec/jni.h>
}

#include "log.h"
#include "jni_utils.h"
#include "event.h"

#define ARRAYLEN(a) (sizeof(a)/sizeof(a[0]))

extern "C" {
    jni_func(void, createNative, jobject appctx);
    jni_func(void, initNative);
    jni_func(void, destroyNative);

    jni_func(void, commandNative, jobjectArray jarray);

    jni_func(void, createAudioNative);
    jni_func(void, initAudioNative);
    jni_func(void, destroyAudioNative);
    jni_func(void, commandAudioNative, jobjectArray jarray);
};

JavaVM *g_vm;
mpv_handle *g_mpv;
std::atomic<bool> g_event_thread_request_exit(false);

mpv_handle *g_audio_mpv = NULL;
std::atomic<bool> g_audio_event_thread_request_exit(false);

static pthread_t event_thread_id;
static pthread_t audio_event_thread_id;
static jobject global_appctx;

static void prepare_environment(JNIEnv *env, jobject appctx) {
    setlocale(LC_NUMERIC, "C");

    g_vm = NULL;
    env->GetJavaVM(&g_vm);
    if (!g_vm)
        die("failed to get jvm");
    av_jni_set_java_vm(g_vm, NULL);

    if (global_appctx)
        env->DeleteGlobalRef(global_appctx);
    global_appctx = env->NewGlobalRef(appctx);
    if (global_appctx)
        av_jni_set_android_app_ctx(global_appctx, NULL);

    init_methods_cache(env);
}

jni_func(void, createNative, jobject appctx) {
    if (g_mpv)
        die("mpv is already initialized");

    prepare_environment(env, appctx);

    g_mpv = mpv_create();
    if (!g_mpv)
        die("context init failed");

    mpv_request_log_messages(g_mpv, "terminal-default");
    mpv_set_option_string(g_mpv, "msg-level", "all=v");
}

jni_func(void, initNative) {
    if (!g_mpv)
        die("mpv is not created");

    if (mpv_initialize(g_mpv) < 0)
        die("mpv init failed");

    g_event_thread_request_exit = false;
    if (pthread_create(&event_thread_id, NULL, event_thread, NULL) != 0)
        die("thread create failed");
    pthread_setname_np(event_thread_id, "event_thread");
}

jni_func(void, destroyNative) {
    if (!g_mpv) {
        ALOGV("mpv destroy called but it's already destroyed");
        return;
    }

    g_event_thread_request_exit = true;
    mpv_wakeup(g_mpv);
    pthread_join(event_thread_id, NULL);

    mpv_terminate_destroy(g_mpv);
    g_mpv = NULL;
}

jni_func(void, commandNative, jobjectArray jarray) {
    CHECK_MPV_INIT();

    jstring strings[64] = {0};
    const char *arguments[64] = {0};
    jsize len = env->GetArrayLength(jarray);
    if (len >= ARRAYLEN(arguments))
        die("too many command arguments");

    for (jsize i = 0; i < len; ++i) {
        strings[i] = (jstring)env->GetObjectArrayElement(jarray, i);
        arguments[i] = env->GetStringUTFChars(strings[i], NULL);
    }

    mpv_command(g_mpv, arguments);

    for (jsize i = 0; i < len; ++i) {
        env->ReleaseStringUTFChars(strings[i], arguments[i]);
        env->DeleteLocalRef(strings[i]);
    }
}

jni_func(void, createAudioNative) {
    if (g_audio_mpv)
        return;

    if (!g_mpv)
        die("main mpv must be initialized before audio mpv");

    g_audio_mpv = mpv_create();
    if (!g_audio_mpv)
        die("audio mpv context init failed");

    // Audio instance is deliberately headless: it must never create or decode a video path.
    mpv_set_option_string(g_audio_mpv, "video", "no");
    mpv_set_option_string(g_audio_mpv, "vo", "null");
    mpv_set_option_string(g_audio_mpv, "audio-display", "no");
}

jni_func(void, initAudioNative) {
    if (!g_audio_mpv)
        die("audio mpv is not created");

    if (mpv_initialize(g_audio_mpv) < 0)
        die("audio mpv init failed");

    g_audio_event_thread_request_exit = false;
    if (pthread_create(&audio_event_thread_id, NULL, audio_event_thread, NULL) != 0)
        die("audio thread create failed");
    pthread_setname_np(audio_event_thread_id, "audio_event_thread");
}

jni_func(void, destroyAudioNative) {
    if (!g_audio_mpv)
        return;

    g_audio_event_thread_request_exit = true;
    mpv_wakeup(g_audio_mpv);
    pthread_join(audio_event_thread_id, NULL);

    mpv_terminate_destroy(g_audio_mpv);
    g_audio_mpv = NULL;
}

jni_func(void, commandAudioNative, jobjectArray jarray) {
    if (!g_audio_mpv)
        return;

    jstring strings[64] = {0};
    const char *arguments[64] = {0};
    jsize len = env->GetArrayLength(jarray);
    if (len >= ARRAYLEN(arguments))
        die("too many audio command arguments");

    for (jsize i = 0; i < len; ++i) {
        strings[i] = (jstring)env->GetObjectArrayElement(jarray, i);
        arguments[i] = env->GetStringUTFChars(strings[i], NULL);
    }

    mpv_command(g_audio_mpv, arguments);

    for (jsize i = 0; i < len; ++i) {
        env->ReleaseStringUTFChars(strings[i], arguments[i]);
        env->DeleteLocalRef(strings[i]);
    }
}
