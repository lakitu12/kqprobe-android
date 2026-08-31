cmake_minimum_required(VERSION 3.22.1)
project(kqprobe CXX)

# libkqprobe.so — JNI probe only. The gfxstream backend and the kumquat server
# (libkqserver.so) are prebuilt binaries dropped into src/main/jniLibs/arm64-v8a/
# by ../scripts/build-native.sh (see README); the backend is found at runtime via
# plain dlopen() from the app's native-lib namespace.
add_library(kqprobe SHARED kqprobe.cpp)
target_compile_features(kqprobe PRIVATE cxx_std_17)
target_link_libraries(kqprobe PRIVATE android log dl)
# NDK sysroot provides the Vulkan loader stub for compile-time headers only;
# -lvulkan links the import lib, symbols resolve to the real loader at runtime.
find_library(vulkan-lib vulkan)
target_link_libraries(kqprobe PRIVATE ${vulkan-lib})
