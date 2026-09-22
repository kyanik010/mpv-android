#pragma once

#include <atomic>

extern JavaVM *g_vm;
extern mpv_handle *g_mpv;
extern std::atomic<bool> g_event_thread_request_exit;

// Independent MPV instance used exclusively for external IPTV audio.
extern mpv_handle *g_audio_mpv;
extern std::atomic<bool> g_audio_event_thread_request_exit;
