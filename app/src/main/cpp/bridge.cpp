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
#include "cheatSystem.h"
#include "utils/colorspacehandler/colorspacehandler.h"
#ifdef HAVE_JIT
#include "arm_jit.h"
#endif
#ifdef ENABLE_OPENGL_ES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include "OGLRender.h"
#include "OGLRender_ES3.h"
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

// The core selects a renderer by its index in this list.
enum { CORE3D_NULL = 0, CORE3D_SOFT = 1, CORE3D_GLES = 2 };

GPU3DInterface *core3DList[] = {
	&gpu3DNull,
	&gpu3DRasterize,
#ifdef ENABLE_OPENGL_ES
	&gpu3Dgl_ES_3_0,
#endif
	NULL
};

#ifdef ENABLE_OPENGL_ES
// ---------------------------------------------------------------------------
// Off-screen OpenGL ES 3.0 context for the core's GPU renderer. It renders into its own
// framebuffer objects, so a 1x1 pbuffer is enough as the context's surface.
// ---------------------------------------------------------------------------
static EGLDisplay g_eglDisplay = EGL_NO_DISPLAY;
static EGLContext g_eglContext = EGL_NO_CONTEXT;
static EGLSurface g_eglSurface = EGL_NO_SURFACE;
static EGLContext g_eglPrevContext = EGL_NO_CONTEXT;
static EGLSurface g_eglPrevDraw = EGL_NO_SURFACE;
static EGLSurface g_eglPrevRead = EGL_NO_SURFACE;
static EGLDisplay g_eglPrevDisplay = EGL_NO_DISPLAY;

static void AndroidGL_Deinit()
{
	if (g_eglDisplay == EGL_NO_DISPLAY) return;
	if (eglGetCurrentContext() == g_eglContext)
		eglMakeCurrent(g_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
	if (g_eglSurface != EGL_NO_SURFACE) eglDestroySurface(g_eglDisplay, g_eglSurface);
	if (g_eglContext != EGL_NO_CONTEXT) eglDestroyContext(g_eglDisplay, g_eglContext);
	g_eglSurface = EGL_NO_SURFACE;
	g_eglContext = EGL_NO_CONTEXT;
	g_eglDisplay = EGL_NO_DISPLAY; // the display itself is shared with the app's own surface: never terminate it
}

static bool AndroidGL_Init()
{
	if (g_eglContext != EGL_NO_CONTEXT) return true;

	g_eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
	if (g_eglDisplay == EGL_NO_DISPLAY || !eglInitialize(g_eglDisplay, NULL, NULL))
	{
		LOGI("EGL: could not initialise the display");
		g_eglDisplay = EGL_NO_DISPLAY;
		return false;
	}

	const EGLint confAttr[] = {
		EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
		EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
		EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
		EGL_NONE
	};
	EGLConfig conf;
	EGLint numConfigs = 0;
	if (!eglChooseConfig(g_eglDisplay, confAttr, &conf, 1, &numConfigs) || numConfigs < 1)
	{
		LOGI("EGL: no OpenGL ES 3 config available");
		g_eglDisplay = EGL_NO_DISPLAY;
		return false;
	}

	const EGLint ctxAttr[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
	g_eglContext = eglCreateContext(g_eglDisplay, conf, EGL_NO_CONTEXT, ctxAttr);
	if (g_eglContext == EGL_NO_CONTEXT)
	{
		LOGI("EGL: could not create an OpenGL ES 3 context (0x%x)", (unsigned)eglGetError());
		g_eglDisplay = EGL_NO_DISPLAY;
		return false;
	}

	const EGLint pbufferAttr[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
	g_eglSurface = eglCreatePbufferSurface(g_eglDisplay, conf, pbufferAttr);
	if (g_eglSurface == EGL_NO_SURFACE)
	{
		LOGI("EGL: could not create the pbuffer surface (0x%x)", (unsigned)eglGetError());
		AndroidGL_Deinit();
		return false;
	}
	LOGI("EGL: OpenGL ES 3 context ready for the 3D renderer");
	return true;
}

static bool AndroidGL_Begin()
{
	if (g_eglContext == EGL_NO_CONTEXT) return false;
	g_eglPrevDisplay = eglGetCurrentDisplay();
	g_eglPrevContext = eglGetCurrentContext();
	g_eglPrevDraw = eglGetCurrentSurface(EGL_DRAW);
	g_eglPrevRead = eglGetCurrentSurface(EGL_READ);
	if (g_eglPrevContext == g_eglContext) return true;
	return eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext) == EGL_TRUE;
}

static void AndroidGL_End()
{
	if (g_eglContext == EGL_NO_CONTEXT || g_eglPrevContext == g_eglContext) return;
	if (g_eglPrevContext != EGL_NO_CONTEXT && g_eglPrevDisplay != EGL_NO_DISPLAY)
		eglMakeCurrent(g_eglPrevDisplay, g_eglPrevDraw, g_eglPrevRead, g_eglPrevContext);
	else
		eglMakeCurrent(g_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

static bool AndroidGL_FramebufferDidResize(const bool isFBOSupported, size_t w, size_t h)
{
	// The renderer draws into framebuffer objects; the pbuffer never needs to change size.
	return isFBOSupported;
}
#endif // ENABLE_OPENGL_ES

static int g_core3D = CORE3D_NULL;

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

#ifdef ENABLE_OPENGL_ES
	OGLLoadEntryPoints_ES_3_0_Func = &OGLLoadEntryPoints_ES_3_0;
	OGLCreateRenderer_ES_3_0_Func = &OGLCreateRenderer_ES_3_0;
	oglrender_init = &AndroidGL_Init;
	oglrender_deinit = &AndroidGL_Deinit;
	oglrender_beginOpenGL = &AndroidGL_Begin;
	oglrender_endOpenGL = &AndroidGL_End;
	oglrender_framebufferDidResizeCallback = &AndroidGL_FramebufferDidResize;
	CommonSettings.GFX3D_Renderer_MultisampleSize = 0;
#endif

	if (GPU->Change3DRendererByID(CORE3D_SOFT))
		g_core3D = CORE3D_SOFT;
	else
	{
		GPU->Change3DRendererByID(CORE3D_NULL);
		g_core3D = CORE3D_NULL;
	}

	slot2_Init();
	slot2_Change(NDS_SLOT2_AUTO);
	backup_setManualBackupType(0); // autodetect

	g_inited = true;
	LOGI("DeSmuME core ready (raster threads: %d, base dir: %s)", threads, g_desmumeBaseDir.c_str());
	return JNI_TRUE;
}

JNIFN(jboolean, gpuRendererAvailable)(JNIEnv *, jclass)
{
#ifdef ENABLE_OPENGL_ES
	return JNI_TRUE;
#else
	return JNI_FALSE;
#endif
}

// Chooses who draws the DS 3D graphics: the GPU (OpenGL ES 3) or the CPU (SoftRasterizer).
// Returns false if the GPU renderer was requested but could not start; the software one is then used.
JNIFN(jboolean, setGpuRenderer)(JNIEnv *, jclass, jboolean enabled)
{
	if (!g_inited) return JNI_FALSE;
	const int wanted = (enabled == JNI_TRUE) ? CORE3D_GLES : CORE3D_SOFT;
	if (wanted == g_core3D) return JNI_TRUE;

#ifdef ENABLE_OPENGL_ES
	if (wanted == CORE3D_GLES)
	{
		if (GPU->Change3DRendererByID(CORE3D_GLES))
		{
			g_core3D = CORE3D_GLES;
			LOGI("3D renderer: OpenGL ES 3");
			return JNI_TRUE;
		}
		LOGI("3D renderer: OpenGL ES 3 failed to start, using the software rasterizer");
	}
#endif
	if (GPU->Change3DRendererByID(CORE3D_SOFT))
		g_core3D = CORE3D_SOFT;
	else
	{
		GPU->Change3DRendererByID(CORE3D_NULL);
		g_core3D = CORE3D_NULL;
	}
	return (wanted == CORE3D_SOFT) ? JNI_TRUE : JNI_FALSE;
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

static inline u16 To565(const u16 c)
{
	// DS colour is xBGR1555 (red in the low bits). Convert to RGB565.
	return (u16)(((c & 0x001F) << 11) | ((c & 0x03E0) << 1) | ((c >> 4) & 0x0020) | ((c >> 10) & 0x001F));
}

// Sets the internal rendering resolution (1 = native 256x192, up to 4x). Applied by the core on the next frame.
JNIFN(void, setResolutionScale)(JNIEnv *, jclass, jint scale)
{
	if (!g_inited) return;
	if (scale < 1) scale = 1;
	if (scale > 4) scale = 4;
	GPU->SetCustomFramebufferSize(GPU_FRAMEBUFFER_NATIVE_WIDTH * scale, GPU_FRAMEBUFFER_NATIVE_HEIGHT * scale);
}

// Copies both screens as RGB565 into a direct ByteBuffer: top screen first, then the touch screen,
// each (256*scale) x (192*scale). If the core has not switched to that size yet, the native image
// is enlarged so the caller always gets the size it asked for.
JNIFN(jboolean, copyFrame)(JNIEnv *env, jclass, jobject buffer, jint scale)
{
	if (!g_inited) return JNI_FALSE;
	if (scale < 1 || scale > 4) return JNI_FALSE;
	u16 *dst = (u16 *)env->GetDirectBufferAddress(buffer);
	jlong cap = env->GetDirectBufferCapacity(buffer);
	const size_t NW = GPU_FRAMEBUFFER_NATIVE_WIDTH, NH = GPU_FRAMEBUFFER_NATIVE_HEIGHT;
	const size_t W = NW * (size_t)scale, H = NH * (size_t)scale;
	const size_t pixPerScreen = W * H;
	if (dst == NULL || cap < (jlong)(pixPerScreen * 2 * sizeof(u16))) return JNI_FALSE;

	const NDSDisplayInfo &info = GPU->GetDisplayInfo();
	const bool customReady = (scale > 1) && info.isCustomSizeRequested && (info.pixelBytes == 2) &&
	                         (info.customWidth == W) && (info.customHeight == H);

	for (int s = 0; s < 2; s++)
	{
		const NDSDisplayID id = (s == 0) ? NDSDisplayID_Main : NDSDisplayID_Touch;
		u16 *out = dst + pixPerScreen * s;
		const float intensity = info.backlightIntensity[id];

		if (customReady && info.customBuffer[id] != NULL)
		{
			u16 *src = (u16 *)info.customBuffer[id];
			if (intensity < 0.999f)
				ColorspaceApplyIntensityToBuffer16<false, false>(src, pixPerScreen, intensity);
			for (size_t i = 0; i < pixPerScreen; i++) out[i] = To565(src[i]);
			continue;
		}

		u16 *src = info.nativeBuffer16[id];
		if (src == NULL)
		{
			memset(out, 0, pixPerScreen * sizeof(u16));
			continue;
		}
		if (intensity < 0.999f)
			ColorspaceApplyIntensityToBuffer16<false, false>(src, NW * NH, intensity);

		if (scale == 1)
		{
			// Simple loop on purpose: the compiler vectorises it with NEON.
			for (size_t i = 0; i < pixPerScreen; i++) out[i] = To565(src[i]);
		}
		else
		{
			for (size_t y = 0; y < NH; y++)
			{
				u16 *row = out + y * (size_t)scale * W;
				for (size_t x = 0; x < NW; x++)
				{
					const u16 c = To565(src[y * NW + x]);
					for (int k = 0; k < scale; k++) row[x * (size_t)scale + k] = c;
				}
				for (int k = 1; k < scale; k++) memcpy(row + (size_t)k * W, row, W * sizeof(u16));
			}
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

// ---------------------------------------------------------------------------
// Cheats. The app keeps its own list per game and pushes the enabled ones here after loading a ROM
// and after every change, so the core never needs to read or write its own cheat files.
// ---------------------------------------------------------------------------
JNIFN(void, cheatsClear)(JNIEnv *, jclass)
{
	if (cheats != NULL) cheats->clear();
}

// Adds an enabled Action Replay code. The code is any text holding pairs of 8-digit hex words
// ("12345678 9ABCDEF0", one pair per line, or joined with '+' or spaces). Returns false if the
// text is not a valid code.
JNIFN(jboolean, cheatsAddActionReplay)(JNIEnv *env, jclass, jstring code, jstring description)
{
	if (cheats == NULL) return JNI_FALSE;
	std::string raw = jstr(env, code);
	std::string desc = jstr(env, description);

	// Keep only complete 8-digit hex words and lay them out as "XXXXXXXX YYYYYYYY" lines.
	std::vector<std::string> words;
	std::string cur;
	for (size_t i = 0; i <= raw.size(); i++)
	{
		const char c = (i < raw.size()) ? raw[i] : ' ';
		if (isxdigit((unsigned char)c))
		{
			cur += (char)toupper((unsigned char)c);
			continue;
		}
		if (cur.size() == 8) words.push_back(cur);
		cur.clear();
	}
	if (words.empty() || (words.size() % 2) != 0 || words.size() / 2 > MAX_XX_CODE) return JNI_FALSE;

	std::string lines;
	for (size_t i = 0; i < words.size(); i += 2)
		lines += words[i] + " " + words[i + 1] + "\n";

	if (desc.size() > 1000) desc.resize(1000);
	std::vector<char> codeBuf(lines.begin(), lines.end());
	codeBuf.push_back(0);
	std::vector<char> descBuf(desc.begin(), desc.end());
	descBuf.push_back(0);
	return cheats->add_AR(&codeBuf[0], &descBuf[0], true) ? JNI_TRUE : JNI_FALSE;
}

JNIFN(jint, cheatsActiveCount)(JNIEnv *, jclass)
{
	return (cheats != NULL) ? (jint)cheats->getActiveCount() : 0;
}

// 12-character title from the ROM header, e.g. for searching the cheat database.
JNIFN(jstring, romTitle)(JNIEnv *env, jclass)
{
	if (!g_romLoaded) return env->NewStringUTF("");
	char title[13];
	memcpy(title, gameInfo.header.gameTile, 12);
	title[12] = 0;
	for (int i = 0; i < 12; i++)
	{
		if (title[i] != 0 && ((unsigned char)title[i] < 0x20 || (unsigned char)title[i] > 0x7E)) title[i] = ' ';
	}
	return env->NewStringUTF(title);
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
