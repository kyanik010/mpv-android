#pragma once

#include <atomic>
#include <pthread.h>
#include <mpv/client.h>

extern JavaVM *g_vm;
extern mpv_handle *g_mpv;
extern std::atomic<bool> g_event_thread_request_exit;

struct MPVInstance {
    mpv_handle *mpv = nullptr;
    pthread_t event_thread_id = 0;
    std::atomic<bool> event_thread_request_exit{false};
};

extern void *audio_event_thread(void *arg);
