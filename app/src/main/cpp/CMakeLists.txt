# DeSmuME core + JNI bridge for Android (also builds on a Linux host for testing).
cmake_minimum_required(VERSION 3.18)
project(desmume_android C CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# Path to upstream "desmume/src" (fetched by scripts/fetch_desmume.sh).
if(NOT DEFINED DESMUME_SRC)
	set(DESMUME_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../desmume-upstream/desmume/src")
endif()
get_filename_component(DESMUME_SRC "${DESMUME_SRC}" ABSOLUTE)
if(NOT EXISTS "${DESMUME_SRC}/NDSSystem.cpp")
	message(FATAL_ERROR "DeSmuME source not found at ${DESMUME_SRC}. Run scripts/fetch_desmume.sh first.")
endif()

option(DESMUME_ENABLE_JIT "Build the dynamic recompiler" ON)

set(S "${DESMUME_SRC}")
set(CORE_SOURCES
	${S}/armcpu.cpp ${S}/arm_instructions.cpp ${S}/thumb_instructions.cpp
	${S}/bios.cpp ${S}/cp15.cpp ${S}/common.cpp ${S}/debug.cpp ${S}/driver.cpp
	${S}/Database.cpp ${S}/emufile.cpp ${S}/encrypt.cpp ${S}/FIFO.cpp
	${S}/firmware.cpp ${S}/GPU.cpp ${S}/mc.cpp ${S}/path.cpp
	${S}/readwrite.cpp ${S}/wifi.cpp ${S}/MMU.cpp ${S}/NDSSystem.cpp
	${S}/ROMReader.cpp ${S}/render3D.cpp ${S}/rtc.cpp ${S}/saves.cpp
	${S}/slot1.cpp ${S}/slot2.cpp ${S}/SPU.cpp ${S}/matrix.cpp ${S}/gfx3d.cpp
	${S}/movie.cpp ${S}/mic.cpp ${S}/cheatSystem.cpp ${S}/texcache.cpp
	${S}/rasterize.cpp ${S}/version.cpp
	${S}/metaspu/metaspu.cpp
	${S}/filter/deposterize.cpp ${S}/filter/xbrz.cpp
	${S}/frontend/modules/Disassembler.cpp
	${S}/utils/advanscene.cpp ${S}/utils/datetime.cpp ${S}/utils/guid.cpp
	${S}/utils/emufat.cpp ${S}/utils/fsnitro.cpp ${S}/utils/xstring.cpp
	${S}/utils/decrypt/crc.cpp ${S}/utils/decrypt/decrypt.cpp ${S}/utils/decrypt/header.cpp
	${S}/utils/task.cpp ${S}/utils/vfat.cpp ${S}/utils/dlditool.cpp
	${S}/utils/libfat/cache.cpp ${S}/utils/libfat/directory.cpp ${S}/utils/libfat/disc.cpp
	${S}/utils/libfat/fatdir.cpp ${S}/utils/libfat/fatfile.cpp ${S}/utils/libfat/filetime.cpp
	${S}/utils/libfat/file_allocation_table.cpp ${S}/utils/libfat/libfat.cpp
	${S}/utils/libfat/libfat_public_api.cpp ${S}/utils/libfat/lock.cpp ${S}/utils/libfat/partition.cpp
	${S}/utils/tinyxml/tinystr.cpp ${S}/utils/tinyxml/tinyxml.cpp
	${S}/utils/tinyxml/tinyxmlerror.cpp ${S}/utils/tinyxml/tinyxmlparser.cpp
	${S}/utils/colorspacehandler/colorspacehandler.cpp
	${S}/addons/slot2_auto.cpp ${S}/addons/slot2_mpcf.cpp ${S}/addons/slot2_paddle.cpp
	${S}/addons/slot2_gbagame.cpp ${S}/addons/slot2_none.cpp ${S}/addons/slot2_rumblepak.cpp
	${S}/addons/slot2_guitarGrip.cpp ${S}/addons/slot2_hcv1000.cpp ${S}/addons/slot2_expMemory.cpp
	${S}/addons/slot2_piano.cpp ${S}/addons/slot2_passme.cpp ${S}/addons/slot1_none.cpp
	${S}/addons/slot1_r4.cpp ${S}/addons/slot1_retail_nand.cpp ${S}/addons/slot1_retail_auto.cpp
	${S}/addons/slot1_retail_mcrom.cpp ${S}/addons/slot1_retail_mcrom_debug.cpp
	${S}/addons/slot1comp_mc.cpp ${S}/addons/slot1comp_rom.cpp ${S}/addons/slot1comp_protocol.cpp
	${S}/libretro-common/compat/compat_strl.c
	${S}/libretro-common/file/file_path.c
	${S}/libretro-common/features/features_cpu.c
	${S}/libretro-common/file/retro_dirent.c
	${S}/libretro-common/file/retro_stat.c
	${S}/libretro-common/rthreads/async_job.c
	${S}/libretro-common/rthreads/rsemaphore.c
	${S}/libretro-common/rthreads/rthreads.c
	${S}/libretro-common/encodings/encoding_utf.c
)

set(IS_ARM OFF)
set(IS_X86 OFF)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|armv7|arm")
	set(IS_ARM ON)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64|i[3-6]86|x86")
	set(IS_X86 ON)
endif()

if(DESMUME_ENABLE_JIT AND IS_ARM)
	list(APPEND CORE_SOURCES ${S}/utils/arm_jit/arm_jit_arm.cpp)
	add_compile_definitions(HAVE_JIT)
elseif(DESMUME_ENABLE_JIT AND IS_X86)
	file(GLOB ASMJIT_SOURCES ${S}/utils/AsmJit/core/*.cpp ${S}/utils/AsmJit/x86/*.cpp)
	list(APPEND CORE_SOURCES ${S}/arm_jit.cpp ${ASMJIT_SOURCES})
	add_compile_definitions(HAVE_JIT)
endif()

# GPU 3D renderer (OpenGL ES 3.0). Needs the GLES3/EGL headers, so it is on for Android only.
if(ANDROID)
	option(DESMUME_ENABLE_GLES "Build the OpenGL ES 3 renderer" ON)
else()
	option(DESMUME_ENABLE_GLES "Build the OpenGL ES 3 renderer" OFF)
endif()
if(DESMUME_ENABLE_GLES)
	list(APPEND CORE_SOURCES ${S}/OGLRender.cpp ${S}/OGLRender_3_2.cpp ${S}/OGLRender_ES3.cpp)
	add_compile_definitions(ENABLE_OPENGL_ES)
endif()

add_library(desmume-jni SHARED
	${CORE_SOURCES}
	shim/shim.cpp
	bridge.cpp
)

target_include_directories(desmume-jni PRIVATE
	shim
	${S}
	${S}/libretro-common/include
	${S}/frontend
)

# Performance flags: the core is CPU bound, so favour speed everywhere.
target_compile_options(desmume-jni PRIVATE
	-O3 -fomit-frame-pointer -fno-strict-aliasing -fvisibility=hidden
	-ffunction-sections -fdata-sections
	-w
)
# EXPERIMENTAL_WIFI_COMM: upstream's opt-in switch for wireless emulation (off unless the app asks).
target_compile_definitions(desmume-jni PRIVATE NDEBUG EXPERIMENTAL_WIFI_COMM)
if(CMAKE_SIZEOF_VOID_P EQUAL 8)
	target_compile_definitions(desmume-jni PRIVATE HOST_64)
else()
	target_compile_definitions(desmume-jni PRIVATE HOST_32)
endif()
if(CMAKE_SYSTEM_PROCESSOR MATCHES "armv7|armeabi")
	target_compile_options(desmume-jni PRIVATE -mfpu=neon)
endif()

find_package(ZLIB REQUIRED)
find_package(Threads REQUIRED)
target_link_libraries(desmume-jni PRIVATE ZLIB::ZLIB Threads::Threads)

# Fail at link time (not at load time) if a core source file is missing.
target_link_options(desmume-jni PRIVATE -Wl,--no-undefined)

if(ANDROID)
	target_link_libraries(desmume-jni PRIVATE log)
	if(DESMUME_ENABLE_GLES)
		target_link_libraries(desmume-jni PRIVATE EGL GLESv3)
	endif()
	target_link_options(desmume-jni PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
else()
	find_package(JNI REQUIRED)
	target_include_directories(desmume-jni PRIVATE ${JNI_INCLUDE_DIRS})
endif()
