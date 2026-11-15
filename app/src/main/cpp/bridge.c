/* mGBA Android port - JNI bridge
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include <jni.h>
#include <ctype.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#include <mgba/core/cheats.h>
#include <mgba/core/config.h>
#include <mgba/core/core.h>
#include <mgba/core/interface.h>
#include <mgba/core/log.h>
#include <mgba/core/rewind.h>
#include <mgba/core/serialize.h>
#include <mgba/gba/interface.h>
#include <mgba-util/audio-buffer.h>
#include <mgba-util/audio-resampler.h>
#include <mgba-util/vfs.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "mgba", __VA_ARGS__)
#else
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

#ifdef BUILD_GLES3
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#endif

#define JNIFN(ret, name) JNIEXPORT ret JNICALL Java_org_mgba_android_Native_##name

#define VIDEO_STRIDE 256
#define VIDEO_MAX_H 224
#define OUT_RATE 48000
#define OUT_CAPACITY 8192
#define REWIND_INTERVAL 2
#define MAX_OPTIONS 64

static char g_baseDir[1024];
static struct mCore* core;
static mColor* g_video;
static struct mAudioBuffer g_out;
static struct mAudioResampler g_resampler;
static bool g_audioReady;

static struct mCoreRewindContext g_rewind;
static bool g_rewindReady;
static int g_rewindSeconds;
static int g_rewindCounter;

// Options set from Java survive across games and are applied to every new core.
static struct { char key[48]; char value[512]; } g_options[MAX_OPTIONS];
static int g_optionCount;

// ---------------------------------------------------------------------------
// Peripherals
// ---------------------------------------------------------------------------
static struct mRumbleIntegrator g_rumble;
static float g_rumbleLevel;
static struct mRotationSource g_rotation;
static int32_t g_tiltX, g_tiltY, g_gyroZ;
static struct GBALuminanceSource g_lux;
static int g_luxLevel;

static void _setRumble(struct mRumbleIntegrator* rumble, float value) {
	(void) rumble;
	g_rumbleLevel = value;
}
static void _rotationSample(struct mRotationSource* source) { (void) source; }
static int32_t _readTiltX(struct mRotationSource* source) { (void) source; return g_tiltX; }
static int32_t _readTiltY(struct mRotationSource* source) { (void) source; return g_tiltY; }
static int32_t _readGyroZ(struct mRotationSource* source) { (void) source; return g_gyroZ; }
static void _luxSample(struct GBALuminanceSource* lux) { (void) lux; }
static uint8_t _readLux(struct GBALuminanceSource* lux) {
	(void) lux;
	// Same scale as the desktop frontends: 0 = dark, 10 = brightest sunlight.
	static const int levels[10] = { 5, 11, 18, 27, 42, 62, 84, 109, 139, 183 };
	int value = 0x16;
	if (g_luxLevel > 0) {
		value += levels[(g_luxLevel > 10 ? 10 : g_luxLevel) - 1];
	}
	return 0xFF - value;
}

// ---------------------------------------------------------------------------
// Logging: only problems are forwarded, the core is very chatty otherwise.
// ---------------------------------------------------------------------------
static void _log(struct mLogger* logger, int category, enum mLogLevel level, const char* format, va_list args) {
	(void) logger;
	if (!(level & (mLOG_FATAL | mLOG_ERROR | mLOG_WARN))) {
		return;
	}
	char buf[512];
	vsnprintf(buf, sizeof(buf), format, args);
	LOGI("[%s] %s", mLogCategoryName(category), buf);
}
static struct mLogger g_logger = { .log = _log };

// ---------------------------------------------------------------------------
// Off-screen OpenGL ES 3 context for the high-resolution GBA renderer
// ---------------------------------------------------------------------------
static int g_hiresScale = 1;   // what Java asked for
static bool g_hiresActive;     // the GL renderer is the one drawing right now

#ifdef BUILD_GLES3
static EGLDisplay g_eglDisplay = EGL_NO_DISPLAY;
static EGLContext g_eglContext = EGL_NO_CONTEXT;
static EGLSurface g_eglSurface = EGL_NO_SURFACE;
static GLuint g_outputTex;
static int g_glDepth;

static bool _glInit(void) {
	if (g_eglContext != EGL_NO_CONTEXT) {
		return true;
	}
	g_eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
	if (g_eglDisplay == EGL_NO_DISPLAY || !eglInitialize(g_eglDisplay, NULL, NULL)) {
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
	EGLint n = 0;
	if (!eglChooseConfig(g_eglDisplay, confAttr, &conf, 1, &n) || n < 1) {
		g_eglDisplay = EGL_NO_DISPLAY;
		return false;
	}
	const EGLint ctxAttr[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
	g_eglContext = eglCreateContext(g_eglDisplay, conf, EGL_NO_CONTEXT, ctxAttr);
	if (g_eglContext == EGL_NO_CONTEXT) {
		g_eglDisplay = EGL_NO_DISPLAY;
		return false;
	}
	const EGLint pbAttr[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
	g_eglSurface = eglCreatePbufferSurface(g_eglDisplay, conf, pbAttr);
	if (g_eglSurface == EGL_NO_SURFACE) {
		eglDestroyContext(g_eglDisplay, g_eglContext);
		g_eglContext = EGL_NO_CONTEXT;
		g_eglDisplay = EGL_NO_DISPLAY;
		return false;
	}
	return true;
}

// Every entry point that can reach the renderer brackets its work with these, because the
// calls come from more than one Java thread and a context can be current on only one.
static bool _glEnter(void) {
	if (g_eglContext == EGL_NO_CONTEXT) {
		return false;
	}
	if (g_glDepth++ > 0) {
		return true;
	}
	if (!eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext)) {
		g_glDepth = 0;
		return false;
	}
	return true;
}

static void _glLeave(void) {
	if (g_eglContext == EGL_NO_CONTEXT || g_glDepth == 0) {
		return;
	}
	if (--g_glDepth == 0) {
		eglMakeCurrent(g_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
	}
}
#else
static bool _glEnter(void) { return false; }
static void _glLeave(void) {}
#endif

// Switches the GBA core between the software renderer and the OpenGL one.
static void _applyHires(void) {
	if (!core) {
		return;
	}
	bool want = g_hiresScale > 1 && core->platform(core) == mPLATFORM_GBA;
#ifdef BUILD_GLES3
	if (want && !_glInit()) {
		LOGI("OpenGL ES 3 is not available: staying on the software renderer");
		want = false;
	}
	if (!want && !g_hiresActive) {
		return;
	}
	if (!_glEnter()) {
		return;
	}
	if (want) {
		if (!g_outputTex) {
			glGenTextures(1, &g_outputTex);
			glBindTexture(GL_TEXTURE_2D, g_outputTex);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			glBindTexture(GL_TEXTURE_2D, 0);
		}
		core->setVideoGLTex(core, g_outputTex);
		mCoreConfigSetIntValue(&core->config, "videoScale", g_hiresScale);
		mCoreConfigSetIntValue(&core->config, "hwaccelVideo", 1);
		if (g_hiresActive) {
			core->reloadConfigOption(core, "videoScale", &core->config);
		} else {
			core->reloadConfigOption(core, "hwaccelVideo", &core->config);
		}
	} else {
		mCoreConfigSetIntValue(&core->config, "hwaccelVideo", 0);
		core->reloadConfigOption(core, "hwaccelVideo", &core->config);
	}
	g_hiresActive = want;
	_glLeave();
#else
	(void) want;
	g_hiresActive = false;
#endif
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
static void _jstr(JNIEnv* env, jstring s, char* out, size_t size) {
	out[0] = '\0';
	if (!s) {
		return;
	}
	const char* c = (*env)->GetStringUTFChars(env, s, NULL);
	if (c) {
		snprintf(out, size, "%s", c);
		(*env)->ReleaseStringUTFChars(env, s, c);
	}
}

static const char* _option(const char* key) {
	int i;
	for (i = 0; i < g_optionCount; ++i) {
		if (strcmp(g_options[i].key, key) == 0) {
			return g_options[i].value;
		}
	}
	return NULL;
}

static void _rewindSetup(void) {
	if (g_rewindReady) {
		mCoreRewindContextDeinit(&g_rewind);
		g_rewindReady = false;
	}
	if (core && g_rewindSeconds > 0) {
		mCoreRewindContextInit(&g_rewind, (size_t) g_rewindSeconds * 60 / REWIND_INTERVAL, false);
		g_rewindReady = true;
		g_rewindCounter = 0;
	}
}

static void _closeCore(void) {
	if (!core) {
		return;
	}
	bool gl = _glEnter();
	if (g_rewindReady) {
		mCoreRewindContextDeinit(&g_rewind);
		g_rewindReady = false;
	}
	core->unloadROM(core);
	mCoreConfigDeinit(&core->config);
	core->deinit(core);
	core = NULL;
	g_hiresActive = false;
#ifdef BUILD_GLES3
	if (gl && g_outputTex) {
		glDeleteTextures(1, &g_outputTex);
		g_outputTex = 0;
	}
#endif
	if (gl) {
		_glLeave();
	}
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------
JNIFN(void, init)(JNIEnv* env, jclass cls, jstring baseDir) {
	(void) cls;
	_jstr(env, baseDir, g_baseDir, sizeof(g_baseDir));
	// The core keeps its own config.ini under $HOME/.config/mgba.
	setenv("HOME", g_baseDir, 1);
	mLogSetDefaultLogger(&g_logger);

	if (!g_video) {
		g_video = calloc(VIDEO_STRIDE * VIDEO_MAX_H, sizeof(mColor));
	}
	if (!g_audioReady) {
		mAudioBufferInit(&g_out, OUT_CAPACITY, 2);
		mAudioResamplerInit(&g_resampler, mINTERPOLATOR_SINC);
		mAudioResamplerSetDestination(&g_resampler, &g_out, OUT_RATE);
		g_audioReady = true;
	}

	mRumbleIntegratorInit(&g_rumble);
	g_rumble.setRumble = _setRumble;
	g_rotation.sample = _rotationSample;
	g_rotation.readTiltX = _readTiltX;
	g_rotation.readTiltY = _readTiltY;
	g_rotation.readGyroZ = _readGyroZ;
	g_lux.sample = _luxSample;
	g_lux.readLuminance = _readLux;
}

JNIFN(jboolean, hiresAvailable)(JNIEnv* env, jclass cls) {
	(void) env; (void) cls;
#ifdef BUILD_GLES3
	return JNI_TRUE;
#else
	return JNI_FALSE;
#endif
}

// Remembers an option and, if a game is running, applies it right away.
JNIFN(void, setOption)(JNIEnv* env, jclass cls, jstring jkey, jstring jvalue) {
	(void) cls;
	char key[48], value[512];
	_jstr(env, jkey, key, sizeof(key));
	_jstr(env, jvalue, value, sizeof(value));
	int i;
	for (i = 0; i < g_optionCount; ++i) {
		if (strcmp(g_options[i].key, key) == 0) {
			break;
		}
	}
	if (i == g_optionCount) {
		if (g_optionCount == MAX_OPTIONS) {
			return;
		}
		++g_optionCount;
		snprintf(g_options[i].key, sizeof(g_options[i].key), "%s", key);
	}
	snprintf(g_options[i].value, sizeof(g_options[i].value), "%s", value);

	if (core) {
		bool gl = _glEnter();
		mCoreConfigSetValue(&core->config, key, value);
		mCoreConfigMap(&core->config, &core->opts);
		core->reloadConfigOption(core, strncmp(key, "gb.pal", 6) == 0 ? "gb.pal" : key, &core->config);
		if (gl) {
			_glLeave();
		}
	}
}

// Returns the platform (0 = GBA, 1 = GB/GBC) or -1 if the file is not a game the core knows.
JNIFN(jint, loadRom)(JNIEnv* env, jclass cls, jstring jpath) {
	(void) cls;
	char path[1024], dir[1100];
	_jstr(env, jpath, path, sizeof(path));
	_closeCore();

	core = mCoreFind(path);
	if (!core) {
		return -1;
	}
	if (!core->init(core)) {
		core = NULL;
		return -1;
	}
	mCoreInitConfig(core, "android");

	struct mCoreOptions opts = {
		.useBios = true,
		.skipBios = true,
		.volume = 0x100,
		.audioBuffers = 4096,
		.logLevel = mLOG_WARN | mLOG_ERROR | mLOG_FATAL,
	};
	mCoreConfigLoadDefaults(&core->config, &opts);
	mCoreConfigSetDefaultIntValue(&core->config, "cheatAutoload", 0);
	mCoreConfigSetDefaultIntValue(&core->config, "cheatAutosave", 0);
	mCoreConfigSetDefaultValue(&core->config, "idleOptimization", "detect");
	mCoreConfigSetDefaultIntValue(&core->config, "sgb.borders", 1);

	// Saves, states and screenshots each get a folder of their own.
	static const char* const folders[][2] = {
		{ "savegamePath", "saves" }, { "savestatePath", "states" }, { "screenshotPath", "screenshots" },
	};
	size_t f;
	for (f = 0; f < sizeof(folders) / sizeof(folders[0]); ++f) {
		snprintf(dir, sizeof(dir), "%s/%s", g_baseDir, folders[f][1]);
		mkdir(dir, 0755);
		mCoreConfigSetValue(&core->config, folders[f][0], dir);
	}
	int i;
	for (i = 0; i < g_optionCount; ++i) {
		mCoreConfigSetValue(&core->config, g_options[i].key, g_options[i].value);
	}
	// The GL renderer is switched on after the game is loaded, never straight from the config.
	mCoreConfigSetIntValue(&core->config, "hwaccelVideo", 0);
	mCoreLoadConfig(core);

	core->setVideoBuffer(core, g_video, VIDEO_STRIDE);
	core->setPeripheral(core, mPERIPH_RUMBLE, &g_rumble.d);
	core->setPeripheral(core, mPERIPH_ROTATION, &g_rotation);
	if (core->platform(core) == mPLATFORM_GBA) {
		core->setPeripheral(core, mPERIPH_GBA_LUMINANCE, &g_lux);
	}

	if (!mCoreLoadFile(core, path)) {
		_closeCore();
		return -1;
	}

	const char* bios = _option(core->platform(core) == mPLATFORM_GBA ? "gba.bios" : "gb.bios");
	if (bios && bios[0] && core->opts.useBios) {
		struct VFile* vf = VFileOpen(bios, O_RDONLY);
		if (vf && !core->loadBIOS(core, vf, 0)) {
			vf->close(vf);
		}
	}

	mCoreAutoloadSave(core);
	mCoreAutoloadPatch(core);
	core->reset(core);
	_applyHires();
	if (g_hiresActive) {
		bool gl = _glEnter();
		core->reset(core);
		if (gl) {
			_glLeave();
		}
	}

	mAudioBufferClear(&g_out);
	g_rumbleLevel = 0;
	_rewindSetup();
	return (jint) core->platform(core);
}

JNIFN(void, closeRom)(JNIEnv* env, jclass cls) {
	(void) env; (void) cls;
	_closeCore();
}

JNIFN(jboolean, romLoaded)(JNIEnv* env, jclass cls) {
	(void) env; (void) cls;
	return core ? JNI_TRUE : JNI_FALSE;
}

JNIFN(void, reset)(JNIEnv* env, jclass cls) {
	(void) env; (void) cls;
	if (!core) {
		return;
	}
	bool gl = _glEnter();
	core->reset(core);
	if (gl) {
		_glLeave();
	}
	mAudioBufferClear(&g_out);
	_rewindSetup();
}

// "Title|CODE" from the cartridge header.
JNIFN(jstring, gameInfo)(JNIEnv* env, jclass cls) {
	(void) cls;
	if (!core) {
		return (*env)->NewStringUTF(env, "");
	}
	struct mGameInfo info;
	memset(&info, 0, sizeof(info));
	core->getGameInfo(core, &info);
	char buf[64];
	snprintf(buf, sizeof(buf), "%s|%s", info.title, info.code);
	size_t i;
	for (i = 0; buf[i]; ++i) {
		if ((unsigned char) buf[i] < 0x20 || (unsigned char) buf[i] > 0x7E) {
			buf[i] = ' ';
		}
	}
	return (*env)->NewStringUTF(env, buf);
}

// ---------------------------------------------------------------------------
// Running
// ---------------------------------------------------------------------------
// Runs one frame. With rewinding set, steps backwards through the rewind buffer instead.
// Returns false only when rewinding and there is nothing left to rewind.
JNIFN(jboolean, runFrame)(JNIEnv* env, jclass cls, jint keys, jboolean rewinding) {
	(void) env; (void) cls;
	if (!core) {
		return JNI_FALSE;
	}
	bool gl = _glEnter();
	jboolean result = JNI_TRUE;
	core->setKeys(core, (uint32_t) keys);

	if (rewinding && g_rewindReady) {
		if (mCoreRewindRestore(&g_rewind, core, 1)) {
			core->runFrame(core);
		} else {
			result = JNI_FALSE;
		}
		// Rewound audio would only be noise.
		mAudioBufferClear(core->getAudioBuffer(core));
	} else {
		core->runFrame(core);
		if (g_rewindReady && ++g_rewindCounter >= REWIND_INTERVAL) {
			g_rewindCounter = 0;
			mCoreRewindAppend(&g_rewind, core);
		}
		mAudioResamplerSetSource(&g_resampler, core->getAudioBuffer(core), core->audioSampleRate(core), true);
		mAudioResamplerProcess(&g_resampler);
	}
	if (gl) {
		_glLeave();
	}
	return result;
}

// Copies the current picture as RGBA bytes into a direct ByteBuffer.
// Returns (width << 16) | height, or 0 if the buffer is too small or nothing is loaded.
JNIFN(jint, copyFrame)(JNIEnv* env, jclass cls, jobject buffer) {
	(void) cls;
	if (!core) {
		return 0;
	}
	uint8_t* dst = (*env)->GetDirectBufferAddress(env, buffer);
	jlong cap = (*env)->GetDirectBufferCapacity(env, buffer);
	unsigned w = 0, h = 0;
	core->currentVideoSize(core, &w, &h);
	if (!dst || !w || !h || cap < (jlong) w * h * 4) {
		return 0;
	}

	const void* src = g_video;
	size_t stride = VIDEO_STRIDE;
	bool gl = false;
	if (g_hiresActive) {
		gl = _glEnter();
		if (!gl) {
			return 0;
		}
		core->getPixels(core, &src, &stride);
	}
	if (src) {
		unsigned y;
		for (y = 0; y < h; ++y) {
			memcpy(dst + (size_t) y * w * 4, (const uint8_t*) src + (size_t) y * stride * 4, (size_t) w * 4);
		}
		// The alpha byte is unused by the core; make the picture opaque.
		size_t i, n = (size_t) w * h;
		for (i = 0; i < n; ++i) {
			dst[i * 4 + 3] = 0xFF;
		}
	}
	if (gl) {
		_glLeave();
	}
	return src ? (jint) ((w << 16) | h) : 0;
}

// Drains resampled audio (48 kHz, interleaved stereo). Returns the number of stereo frames.
JNIFN(jint, readAudio)(JNIEnv* env, jclass cls, jshortArray out) {
	(void) cls;
	if (!g_audioReady) {
		return 0;
	}
	static int16_t tmp[OUT_CAPACITY * 2];
	jsize max = (*env)->GetArrayLength(env, out) / 2;
	if (max > OUT_CAPACITY) {
		max = OUT_CAPACITY;
	}
	size_t n = mAudioBufferRead(&g_out, tmp, (size_t) max);
	if (n > 0) {
		(*env)->SetShortArrayRegion(env, out, 0, (jsize) (n * 2), tmp);
	}
	return (jint) n;
}

JNIFN(void, clearAudio)(JNIEnv* env, jclass cls) {
	(void) env; (void) cls;
	if (g_audioReady) {
		mAudioBufferClear(&g_out);
	}
}

// ---------------------------------------------------------------------------
// Save states, screenshots, rewind
// ---------------------------------------------------------------------------
JNIFN(jboolean, saveState)(JNIEnv* env, jclass cls, jint slot) {
	(void) env; (void) cls;
	if (!core) {
		return JNI_FALSE;
	}
	bool gl = _glEnter();
	bool ok = mCoreSaveState(core, slot, SAVESTATE_SCREENSHOT | SAVESTATE_SAVEDATA | SAVESTATE_RTC | SAVESTATE_METADATA);
	if (gl) {
		_glLeave();
	}
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIFN(jboolean, loadState)(JNIEnv* env, jclass cls, jint slot) {
	(void) env; (void) cls;
	if (!core) {
		return JNI_FALSE;
	}
	bool gl = _glEnter();
	bool ok = mCoreLoadState(core, slot, SAVESTATE_SCREENSHOT | SAVESTATE_RTC);
	if (gl) {
		_glLeave();
	}
	mAudioBufferClear(&g_out);
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIFN(jboolean, stateExists)(JNIEnv* env, jclass cls, jint slot) {
	(void) env; (void) cls;
	if (!core) {
		return JNI_FALSE;
	}
	struct VFile* vf = mCoreGetState(core, slot, false);
	if (!vf) {
		return JNI_FALSE;
	}
	vf->close(vf);
	return JNI_TRUE;
}

// Writes a PNG into the screenshots folder.
JNIFN(jboolean, screenshot)(JNIEnv* env, jclass cls, jstring jpath) {
	(void) cls;
	if (!core) {
		return JNI_FALSE;
	}
	char path[1024];
	_jstr(env, jpath, path, sizeof(path));
	struct VFile* vf = VFileOpen(path, O_CREAT | O_TRUNC | O_WRONLY);
	if (!vf) {
		return JNI_FALSE;
	}
	bool gl = _glEnter();
	bool ok = mCoreTakeScreenshotVF(core, vf);
	if (gl) {
		_glLeave();
	}
	vf->close(vf);
	return ok ? JNI_TRUE : JNI_FALSE;
}

// 0 turns rewinding off.
JNIFN(void, setRewindSeconds)(JNIEnv* env, jclass cls, jint seconds) {
	(void) env; (void) cls;
	if (seconds < 0) {
		seconds = 0;
	}
	if (seconds > 120) {
		seconds = 120;
	}
	if (seconds == g_rewindSeconds) {
		return;
	}
	g_rewindSeconds = seconds;
	_rewindSetup();
}

// 1 = software renderer; 2 and up = OpenGL renderer at that multiple of the GBA resolution.
JNIFN(void, setHiresScale)(JNIEnv* env, jclass cls, jint scale) {
	(void) env; (void) cls;
	if (scale < 1) {
		scale = 1;
	}
	if (scale > 6) {
		scale = 6;
	}
	g_hiresScale = scale;
	_applyHires();
}

JNIFN(jboolean, hiresActive)(JNIEnv* env, jclass cls) {
	(void) env; (void) cls;
	return g_hiresActive ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Cheats
// ---------------------------------------------------------------------------
JNIFN(void, cheatsClear)(JNIEnv* env, jclass cls) {
	(void) env; (void) cls;
	if (core) {
		mCheatDeviceClear(core->cheatDevice(core));
	}
}

// Adds one named cheat. The code may hold several lines; separators can be new lines, spaces
// or '+' (the libretro database style). Returns how many lines the core accepted.
JNIFN(jint, cheatsAdd)(JNIEnv* env, jclass cls, jstring jname, jstring jcode) {
	(void) cls;
	if (!core) {
		return 0;
	}
	char name[256];
	_jstr(env, jname, name, sizeof(name));
	const char* raw = (*env)->GetStringUTFChars(env, jcode, NULL);
	if (!raw) {
		return 0;
	}
	char* text = strdup(raw);
	(*env)->ReleaseStringUTFChars(env, jcode, raw);
	if (!text) {
		return 0;
	}

	struct mCheatDevice* device = core->cheatDevice(core);
	struct mCheatSet* set = device->createSet(device, name);
	bool gba = core->platform(core) == mPLATFORM_GBA;
	int accepted = 0;
	char pending[32] = "";
	char line[64];
	char* save = NULL;
	char* tok;
	for (tok = strtok_r(text, " \t\r\n+", &save); tok; tok = strtok_r(NULL, " \t\r\n+", &save)) {
		size_t len = strlen(tok);
		if (len >= sizeof(pending)) {
			continue;
		}
		if (!gba) {
			// Game Boy codes are one token each (XXX-XXX-XXX or 8 hex digits).
			accepted += mCheatAddLine(set, tok, 0) ? 1 : 0;
			continue;
		}
		if (pending[0]) {
			// Second half of an "XXXXXXXX YYYYYYYY" / "XXXXXXXX YYYY" pair.
			snprintf(line, sizeof(line), "%s %s", pending, tok);
			pending[0] = '\0';
			accepted += mCheatAddLine(set, line, 0) ? 1 : 0;
		} else if (len == 8) {
			snprintf(pending, sizeof(pending), "%s", tok);
		} else if (len == 16 || len == 12) {
			snprintf(line, sizeof(line), "%.8s %s", tok, tok + 8);
			accepted += mCheatAddLine(set, line, 0) ? 1 : 0;
		} else {
			accepted += mCheatAddLine(set, tok, 0) ? 1 : 0;
		}
	}
	free(text);

	if (accepted > 0) {
		set->enabled = true;
		mCheatAddSet(device, set);
	} else {
		mCheatSetDeinit(set);
	}
	return accepted;
}

// ---------------------------------------------------------------------------
// Sensors and rumble
// ---------------------------------------------------------------------------
// x, y: acceleration in g (-1..1); z: rotation speed in rad/s.
JNIFN(void, setMotion)(JNIEnv* env, jclass cls, jfloat x, jfloat y, jfloat gyroZ) {
	(void) env; (void) cls;
	g_tiltX = (int32_t) (x * 3e8f);
	g_tiltY = (int32_t) (y * -3e8f);
	g_gyroZ = (int32_t) (gyroZ * -5.5e8f);
}

JNIFN(void, setLuminance)(JNIEnv* env, jclass cls, jint level) {
	(void) env; (void) cls;
	g_luxLevel = level < 0 ? 0 : (level > 10 ? 10 : level);
}

// 0..1, how strongly the cartridge motor ran during the last frame.
JNIFN(jfloat, rumble)(JNIEnv* env, jclass cls) {
	(void) env; (void) cls;
	return g_rumbleLevel;
}

// ---------------------------------------------------------------------------
// Video layers and audio channels (the "Audio/Video > layers" toggles of the desktop app)
// ---------------------------------------------------------------------------
static jobjectArray _channelNames(JNIEnv* env, bool video) {
	jclass str = (*env)->FindClass(env, "java/lang/String");
	const struct mCoreChannelInfo* info = NULL;
	size_t n = 0;
	if (core) {
		n = video ? core->listVideoLayers(core, &info) : core->listAudioChannels(core, &info);
	}
	jobjectArray out = (*env)->NewObjectArray(env, (jsize) n, str, NULL);
	size_t i;
	for (i = 0; i < n; ++i) {
		char buf[96];
		snprintf(buf, sizeof(buf), "%u|%s", (unsigned) info[i].id, info[i].visibleName ? info[i].visibleName : "?");
		jstring s = (*env)->NewStringUTF(env, buf);
		(*env)->SetObjectArrayElement(env, out, (jsize) i, s);
		(*env)->DeleteLocalRef(env, s);
	}
	return out;
}

// Each entry is "id|name".
JNIFN(jobjectArray, videoLayers)(JNIEnv* env, jclass cls) {
	(void) cls;
	return _channelNames(env, true);
}

JNIFN(jobjectArray, audioChannels)(JNIEnv* env, jclass cls) {
	(void) cls;
	return _channelNames(env, false);
}

JNIFN(void, enableVideoLayer)(JNIEnv* env, jclass cls, jint id, jboolean on) {
	(void) env; (void) cls;
	if (core) {
		core->enableVideoLayer(core, (size_t) id, on == JNI_TRUE);
	}
}

JNIFN(void, enableAudioChannel)(JNIEnv* env, jclass cls, jint id, jboolean on) {
	(void) env; (void) cls;
	if (core) {
		core->enableAudioChannel(core, (size_t) id, on == JNI_TRUE);
	}
}
