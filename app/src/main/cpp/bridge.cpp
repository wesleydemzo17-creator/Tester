/*
	DeSmuME Android port - JNI bridge
	Copyright (C) 2026 DeSmuME team and contributors

	This file is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 2 of the License, or
	(at your option) any later version.

	This file is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.
*/

#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <string>
#include <vector>

#include "NDSSystem.h"
#include "GPU.h"
#include "SPU.h"
#include "MMU.h"
#include "driver.h"
#include "render3D.h"
#include "rasterize.h"
#include "saves.h"
#include "movie.h"
#include "slot1.h"
#include "slot2.h"
#include "mc.h"
#include "utils/colorspacehandler/colorspacehandler.h"
#ifdef HAVE_JIT
#include "arm_jit.h"
#endif

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "desmume", __VA_ARGS__)
#else
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

extern std::string g_desmumeBaseDir; // shim.cpp

// ---------------------------------------------------------------------------
// Globals the core expects every frontend to provide.
// ---------------------------------------------------------------------------
volatile bool execute = false;

#define SNDCORE_ANDROID 1

// Audio: the core pushes interleaved stereo s16 here, Java drains it once per frame.
static const size_t AUDIO_CAPACITY_FRAMES = DESMUME_SAMPLE_RATE / 4; // 250 ms
static std::vector<s16> g_audio;      // interleaved L/R
static size_t g_audioFrames = 0;
static bool g_audioMuted = false;
// In the core's asynchronous mode the mixer produces as many samples as the output says it has
// room for, so we hand it exactly one emulated frame's worth (44100 / 59.8261) per runFrame().
static const double AUDIO_FRAMES_PER_VIDEO_FRAME = (double)DESMUME_SAMPLE_RATE / 59.8261;
static double g_audioAccum = 0.0;
static size_t g_audioQuota = 0;

static int AndroidSnd_Init(int)
{
	g_audio.assign(AUDIO_CAPACITY_FRAMES * 2, 0);
	g_audioFrames = 0;
	return 0;
}
static void AndroidSnd_DeInit() {}
static void AndroidSnd_UpdateAudio(s16 *buffer, u32 num_samples)
{
	g_audioQuota = (num_samples < g_audioQuota) ? (g_audioQuota - num_samples) : 0;
	if (g_audioMuted) return;
	size_t space = AUDIO_CAPACITY_FRAMES - g_audioFrames;
	size_t n = (num_samples < space) ? num_samples : space;
	memcpy(&g_audio[g_audioFrames * 2], buffer, n * 2 * sizeof(s16));
	g_audioFrames += n;
}
static u32 AndroidSnd_GetAudioSpace()
{
	size_t space = AUDIO_CAPACITY_FRAMES - g_audioFrames;
	return (u32)((g_audioQuota < space) ? g_audioQuota : space);
}
static void AndroidSnd_Mute() {}
static void AndroidSnd_UnMute() {}
static void AndroidSnd_SetVolume(int) {}
static void AndroidSnd_Clear() { g_audioFrames = 0; }

static SoundInterface_struct SNDAndroid = {
	SNDCORE_ANDROID,
	"Android AudioTrack bridge",
	AndroidSnd_Init,
	AndroidSnd_DeInit,
	AndroidSnd_UpdateAudio,
	AndroidSnd_GetAudioSpace,
	AndroidSnd_Mute,
	AndroidSnd_UnMute,
	AndroidSnd_SetVolume,
	AndroidSnd_Clear,
	NULL,
	NULL
};

SoundInterface_struct *SNDCoreList[] = {
	&SNDDummy,
	&SNDAndroid,
	NULL
};

GPU3DInterface *core3DList[] = {
	&gpu3DNull,
	&gpu3DRasterize,
	NULL
};

// ---------------------------------------------------------------------------
static bool g_inited = false;
static bool g_romLoaded = false;

static std::string jstr(JNIEnv *env, jstring s)
{
	if (s == NULL) return std::string();
	const char *c = env->GetStringUTFChars(s, NULL);
	std::string out = c ? c : "";
	if (c) env->ReleaseStringUTFChars(s, c);
	return out;
}

#define JNIFN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_org_desmume_android_Native_##name

// Key bit layout shared with Java (Native.KEY_*).
enum
{
	KEY_A = 1 << 0, KEY_B = 1 << 1, KEY_SELECT = 1 << 2, KEY_START = 1 << 3,
	KEY_RIGHT = 1 << 4, KEY_LEFT = 1 << 5, KEY_UP = 1 << 6, KEY_DOWN = 1 << 7,
	KEY_R = 1 << 8, KEY_L = 1 << 9, KEY_X = 1 << 10, KEY_Y = 1 << 11,
	KEY_LID = 1 << 12
};

JNIFN(jboolean, init)(JNIEnv *env, jclass, jstring baseDir, jint rasterThreads)
{
	if (g_inited) return JNI_TRUE;

	g_desmumeBaseDir = jstr(env, baseDir);

	if (driver == NULL) driver = new BaseDriver();

	// --- Performance-oriented defaults for mobile CPUs -------------------
	long cores = sysconf(_SC_NPROCESSORS_ONLN);
	if (cores < 1) cores = 1;
	int threads = (rasterThreads > 0) ? rasterThreads : (int)((cores > 4) ? 4 : cores);
	CommonSettings.num_cores = threads;                 // SoftRasterizer worker threads
	CommonSettings.GFX3D_HighResolutionInterpolateColor = false;
	CommonSettings.GFX3D_Renderer_TextureScalingFactor = 1;
	CommonSettings.GFX3D_Renderer_TextureDeposterize = false;
	CommonSettings.GFX3D_Renderer_TextureSmoothing = false;
	CommonSettings.spu_advanced = false;
	CommonSettings.spuInterpolationMode = 1;            // linear
	CommonSettings.use_jit = false;                     // set by setJit() before loading a ROM

	if (NDS_Init() != 0)
	{
		LOGI("NDS_Init failed");
		return JNI_FALSE;
	}

	SPU_ChangeSoundCore(SNDCORE_ANDROID, 735 * 4);
	SPU_SetSynchMode(0, 0);
	SPU_SetVolume(100);

	if (!GPU->Change3DRendererByID(RENDERID_SOFTRASTERIZER))
		GPU->Change3DRendererByID(RENDERID_NULL);

	slot2_Init();
	slot2_Change(NDS_SLOT2_AUTO);
	backup_setManualBackupType(0); // autodetect

	g_inited = true;
	LOGI("DeSmuME core ready (raster threads: %d, base dir: %s)", threads, g_desmumeBaseDir.c_str());
	return JNI_TRUE;
}

JNIFN(jboolean, jitAvailable)(JNIEnv *, jclass)
{
#ifdef HAVE_JIT
	return JNI_TRUE;
#else
	return JNI_FALSE;
#endif
}

// Takes effect on the next loadRom()/reset().
JNIFN(void, setJit)(JNIEnv *, jclass, jboolean enabled, jint blockSize)
{
#ifdef HAVE_JIT
	CommonSettings.use_jit = (enabled == JNI_TRUE);
	if (blockSize < 1) blockSize = 1;
	if (blockSize > 100) blockSize = 100;
	CommonSettings.jit_max_block_size = (u32)blockSize;
#else
	CommonSettings.use_jit = false;
#endif
}

JNIFN(void, setAdvancedTiming)(JNIEnv *, jclass, jboolean enabled)
{
	CommonSettings.advanced_timing = (enabled == JNI_TRUE);
}

JNIFN(void, setSoundEnabled)(JNIEnv *, jclass, jboolean enabled)
{
	g_audioMuted = (enabled != JNI_TRUE);
	if (g_audioMuted) g_audioFrames = 0;
}

JNIFN(jint, loadRom)(JNIEnv *env, jclass, jstring path)
{
	if (!g_inited) return -1;
	std::string p = jstr(env, path);
	execute = false;
	g_romLoaded = false;
	int r = NDS_LoadROM(p.c_str());
	if (r <= 0)
	{
		LOGI("NDS_LoadROM failed (%d): %s", r, p.c_str());
		return (r == 0) ? -1 : r;
	}
	g_audioFrames = 0;
	g_romLoaded = true;
	execute = true;
	LOGI("ROM loaded: %s (JIT %s)", p.c_str(), CommonSettings.use_jit ? "on" : "off");
	return 1;
}

JNIFN(void, closeRom)(JNIEnv *, jclass)
{
	if (!g_inited) return;
	execute = false;
	if (g_romLoaded) NDS_FreeROM();
	g_romLoaded = false;
}

JNIFN(void, reset)(JNIEnv *, jclass)
{
	if (!g_romLoaded) return;
	NDS_Reset();
	g_audioFrames = 0;
	execute = true;
}

JNIFN(jboolean, romLoaded)(JNIEnv *, jclass)
{
	return g_romLoaded ? JNI_TRUE : JNI_FALSE;
}

// Runs exactly one DS frame.
JNIFN(void, runFrame)(JNIEnv *, jclass, jint keys, jboolean touching, jint tx, jint ty, jboolean skipVideo)
{
	if (!g_romLoaded) return;

	NDS_setPad((keys & KEY_RIGHT) != 0, (keys & KEY_LEFT) != 0, (keys & KEY_DOWN) != 0, (keys & KEY_UP) != 0,
	           (keys & KEY_SELECT) != 0, (keys & KEY_START) != 0, (keys & KEY_B) != 0, (keys & KEY_A) != 0,
	           (keys & KEY_Y) != 0, (keys & KEY_X) != 0, (keys & KEY_L) != 0, (keys & KEY_R) != 0,
	           false, (keys & KEY_LID) != 0);

	if (touching)
	{
		if (tx < 0) tx = 0; if (tx > 255) tx = 255;
		if (ty < 0) ty = 0; if (ty > 191) ty = 191;
		NDS_setTouchPos((u16)tx, (u16)ty);
	}
	else
	{
		NDS_releaseTouch();
	}

	NDS_beginProcessingInput();
	FCEUMOV_AddInputState();
	NDS_endProcessingInput();

	if (skipVideo) NDS_SkipNextFrame();

	execute = true;
	NDS_exec<false>();

	g_audioAccum += AUDIO_FRAMES_PER_VIDEO_FRAME;
	g_audioQuota = (size_t)g_audioAccum;
	g_audioAccum -= (double)g_audioQuota;
	SPU_Emulate_user();
}

// Copies both screens (256x384) as RGB565 into a direct ByteBuffer of at least 256*384*2 bytes.
JNIFN(jboolean, copyFrame)(JNIEnv *env, jclass, jobject buffer)
{
	if (!g_inited) return JNI_FALSE;
	u16 *dst = (u16 *)env->GetDirectBufferAddress(buffer);
	jlong cap = env->GetDirectBufferCapacity(buffer);
	const size_t pixPerScreen = GPU_FRAMEBUFFER_NATIVE_WIDTH * GPU_FRAMEBUFFER_NATIVE_HEIGHT;
	if (dst == NULL || cap < (jlong)(pixPerScreen * 2 * sizeof(u16))) return JNI_FALSE;

	const NDSDisplayInfo &info = GPU->GetDisplayInfo();
	for (int s = 0; s < 2; s++)
	{
		const NDSDisplayID id = (s == 0) ? NDSDisplayID_Main : NDSDisplayID_Touch;
		u16 *src = info.nativeBuffer16[id];
		u16 *out = dst + pixPerScreen * s;
		if (src == NULL)
		{
			memset(out, 0, pixPerScreen * sizeof(u16));
			continue;
		}
		float intensity = info.backlightIntensity[id];
		if (intensity < 0.999f)
			ColorspaceApplyIntensityToBuffer16<false, false>(src, pixPerScreen, intensity);

		// DS native is xBGR1555 (red in the low bits). Convert to RGB565.
		// Simple loop on purpose: the compiler vectorises it with NEON.
		for (size_t i = 0; i < pixPerScreen; i++)
		{
			const u16 c = src[i];
			out[i] = (u16)(((c & 0x001F) << 11) | ((c & 0x03E0) << 1) | ((c >> 4) & 0x0020) | ((c >> 10) & 0x001F));
		}
	}
	return JNI_TRUE;
}

// Drains queued audio into a short[] (interleaved stereo). Returns number of stereo frames.
JNIFN(jint, readAudio)(JNIEnv *env, jclass, jshortArray out)
{
	if (g_audioFrames == 0) return 0;
	jsize maxFrames = env->GetArrayLength(out) / 2;
	size_t n = (g_audioFrames < (size_t)maxFrames) ? g_audioFrames : (size_t)maxFrames;
	env->SetShortArrayRegion(out, 0, (jsize)(n * 2), (const jshort *)&g_audio[0]);
	size_t rest = g_audioFrames - n;
	if (rest > 0) memmove(&g_audio[0], &g_audio[n * 2], rest * 2 * sizeof(s16));
	g_audioFrames = rest;
	return (jint)n;
}

JNIFN(jboolean, saveState)(JNIEnv *env, jclass, jstring path)
{
	if (!g_romLoaded) return JNI_FALSE;
	return savestate_save(jstr(env, path).c_str()) ? JNI_TRUE : JNI_FALSE;
}

JNIFN(jboolean, loadState)(JNIEnv *env, jclass, jstring path)
{
	if (!g_romLoaded) return JNI_FALSE;
	bool ok = savestate_load(jstr(env, path).c_str());
	g_audioFrames = 0;
	return ok ? JNI_TRUE : JNI_FALSE;
}

// Pushes pending battery-save (.dsv) writes to disk; call when the app goes to background.
JNIFN(void, flushSaves)(JNIEnv *, jclass)
{
	if (g_romLoaded) MMU_new.backupDevice.flushBackup();
}

JNIFN(void, shutdown)(JNIEnv *, jclass)
{
	if (!g_inited) return;
	execute = false;
	if (g_romLoaded) NDS_FreeROM();
	g_romLoaded = false;
	NDS_DeInit();
	g_inited = false;
}
