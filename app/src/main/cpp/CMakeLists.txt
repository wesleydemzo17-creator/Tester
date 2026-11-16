# Both emulator cores in one app: DeSmuME (Nintendo DS) and mGBA (Game Boy / Color / Advance).
# Each one is its own shared library with its own JNI bridge.
cmake_minimum_required(VERSION 3.18)
project(emulators C CXX)

add_subdirectory(desmume)
add_subdirectory(mgba)
