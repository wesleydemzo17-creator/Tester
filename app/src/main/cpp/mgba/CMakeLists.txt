# mGBA core (built as a static library from the official source) + JNI bridge.
# Also builds on a Linux host for testing.
cmake_minimum_required(VERSION 3.18)
project(mgba_android C CXX)

if(NOT DEFINED MGBA_SRC)
	set(MGBA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../mgba-upstream")
endif()
get_filename_component(MGBA_SRC "${MGBA_SRC}" ABSOLUTE)
if(NOT EXISTS "${MGBA_SRC}/CMakeLists.txt")
	message(FATAL_ERROR "mGBA source not found at ${MGBA_SRC}. Run scripts/fetch_mgba.sh first.")
endif()

if(NOT CMAKE_BUILD_TYPE)
	set(CMAKE_BUILD_TYPE Release)
endif()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Library only: no desktop frontends. zlib comes from the system (the NDK has it); libpng and
# LZMA (7z) come from the copies bundled with mGBA when the system has none.
foreach(opt BUILD_QT BUILD_SDL BUILD_LIBRETRO BUILD_PERF BUILD_TEST BUILD_SUITE BUILD_CINEMA
		BUILD_HEADLESS BUILD_EXAMPLE BUILD_PYTHON BUILD_SHARED BUILD_GL BUILD_GLES2 BUILD_LTO
		USE_FFMPEG USE_EDITLINE USE_DISCORD_RPC USE_LUA USE_JSON_C USE_FREETYPE USE_ELF
		USE_MINIZIP USE_LIBZIP USE_SQLITE3 ENABLE_SCRIPTING ENABLE_DEBUGGERS ENABLE_GDB_STUB)
	set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(USE_EPOXY OFF CACHE STRING "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(USE_ZLIB ON CACHE BOOL "" FORCE)
set(USE_PNG ON CACHE BOOL "" FORCE)
set(USE_LZMA ON CACHE BOOL "" FORCE)
if(ANDROID)
	# High-resolution GBA renderer.
	set(BUILD_GLES3 ON CACHE BOOL "" FORCE)
else()
	set(BUILD_GLES3 OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory("${MGBA_SRC}" mgba-core)

add_library(mgba-jni SHARED bridge.c)
# The bridge must see the same feature macros as the core: they change struct layouts.
get_target_property(MGBA_DEFS mgba COMPILE_DEFINITIONS)
target_compile_definitions(mgba-jni PRIVATE ${MGBA_DEFS})
target_include_directories(mgba-jni PRIVATE "${MGBA_SRC}/include" "${MGBA_SRC}/src" "${CMAKE_CURRENT_BINARY_DIR}/mgba-core/include")
target_compile_options(mgba-jni PRIVATE -O2 -fvisibility=hidden -Wall)
target_link_libraries(mgba-jni PRIVATE mgba)
target_link_options(mgba-jni PRIVATE -Wl,--no-undefined)

if(ANDROID)
	target_link_libraries(mgba-jni PRIVATE log EGL GLESv3)
	target_link_options(mgba-jni PRIVATE -Wl,-z,max-page-size=16384)
else()
	find_package(JNI REQUIRED)
	target_include_directories(mgba-jni PRIVATE ${JNI_INCLUDE_DIRS})
endif()
