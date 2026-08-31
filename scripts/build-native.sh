#!/usr/bin/env bash
# build-native.sh — build the two prebuilt native deps for the kqprobe APK:
#   1. libgfxstream_backend.so  (bionic, gfxstream host backend -> Mali vendor VK)
#   2. libkqserver.so           (bionic, rutabaga_gfx kumquat server as cdylib)
# Output: app/src/main/jniLibs/arm64-v8a/   (gitignored binaries)
#
# Pinned upstreams + local patches (patches/ in repo root):
#   gfxstream    google/gfxstream        @ 5b92790 (0001-kqprobe-native_window-android...)
#   rutabaga     magma-gpu/rutabaga_gfx  @ 13cde31 (0001-kqprobe-cdylib-server-JNI...)
#
# Requirements (Debian/Ubuntu dev machine):
#   Android NDK r26+ (env ANDROID_NDK_HOME or auto-detect ~/Android/Sdk/ndk/*)
#   meson >= 1.0, ninja, pkg-config, python3
#   rustup with target aarch64-linux-android (cargo cross via NDK clang linker)
set -euo pipefail
cd "$(dirname "$0")/.."   # repo root
REPO=$PWD
SRC=$REPO/native/build
OUT=$REPO/app/src/main/jniLibs/arm64-v8a
GFXSTREAM_REV=5b927901d40334cb044ad30b7ca4fa4ba08994b6
RUTABAGA_REV=13cde31787f65f1b5521b2a7ab54f56099cdc09b

# --- NDK autodetect -----------------------------------------------------------
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  NDK=$(ls -d "$HOME"/Android/Sdk/ndk/* 2>/dev/null | sort -V | tail -1)
  [ -n "$NDK" ] || { echo "FATAL: no NDK found; set ANDROID_NDK_HOME"; exit 1; }
  export ANDROID_NDK_HOME="$NDK"
fi
LLVM="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
SYSROOT="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
echo "NDK: $ANDROID_NDK_HOME"

mkdir -p "$SRC" "$OUT"

# --- 1. gfxstream backend ------------------------------------------------------
if [ ! -d "$SRC/gfxstream/.git" ]; then
  git clone --filter=blob:none https://github.com/google/gfxstream.git "$SRC/gfxstream"
fi
git -C "$SRC/gfxstream" checkout -q "$GFXSTREAM_REV"
git -C "$SRC/gfxstream" apply --check "$REPO/patches/0001-kqprobe-native_window-android-system-branch.patch" 2>/dev/null \
  && git -C "$SRC/gfxstream" apply "$REPO/patches/0001-kqprobe-native_window-android-system-branch.patch" \
  || echo "(gfxstream patch already applied)"

# stub cutils/native_handle.h (AOSP-only header; compile-only, backend never calls it)
STUB="$SRC/gfxstream-stub"
mkdir -p "$STUB/cutils"
cp "$REPO/scripts/native/cutils_stub.h" "$STUB/cutils/native_handle.h"

cat > "$SRC/gfxstream-android.ini" <<INI
[binaries]
c = '$LLVM/aarch64-linux-android34-clang'
cpp = '$LLVM/aarch64-linux-android34-clang++'
ar = '$LLVM/llvm-ar'
strip = '$LLVM/llvm-strip'
pkg-config = '/usr/bin/pkg-config'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[built-in options]
# HAVE_MEMFD_CREATE: meson's -DHAVE_MEMFD_CREATE=1 is only added on the
# system()==linux branch; the android cross-file falls through, which would
# compile SharedMemory_posix.cpp WITHOUT MFD_ALLOW_SEALING/F_ADD_SEALS ->
# UDMABUF_CREATE fails EINVAL (kernel requires F_SEAL_SHRINK). Upstream
# Android.bp defines it for the android target; mirror that here.
c_args = ['-DANDROID', '-DVK_USE_PLATFORM_ANDROID_KHR', '-DHAVE_MEMFD_CREATE=1', '-I$SRC/gfxstream/third_party/android/include', '-I$STUB', '-I$SRC/gfxstream/third_party/x11/include', '-I$SRC/gfxstream/third_party/opengl/include', '-Wno-macro-redefined']
cpp_args = ['-DANDROID', '-DVK_USE_PLATFORM_ANDROID_KHR', '-DHAVE_MEMFD_CREATE=1', '-I$SRC/gfxstream/third_party/android/include', '-I$STUB', '-I$SRC/gfxstream/third_party/x11/include', '-I$SRC/gfxstream/third_party/opengl/include', '-Wno-macro-redefined', '-std=c++17']
c_link_args = ['-llog', '-landroid', '-lnativewindow']
cpp_link_args = ['-llog', '-landroid', '-lnativewindow']

[properties]
needs_exe_wrapper = true
sys_root = '$SYSROOT'
INI

if [ ! -f "$SRC/gfxstream-build/host/libgfxstream_backend.so" ]; then
  meson setup "$SRC/gfxstream-build" "$SRC/gfxstream" \
    -Ddecoders=gles,vulkan,composer -Ddefault_library=shared \
    --cross-file "$SRC/gfxstream-android.ini" --buildtype=release
  ninja -C "$SRC/gfxstream-build"
fi
cp "$SRC/gfxstream-build/host/libgfxstream_backend.so" "$OUT/"

# --- 2. rutabaga_gfx kumquat server (cdylib) ------------------------------------
if [ ! -d "$SRC/rutabaga_gfx/.git" ]; then
  git clone https://github.com/magma-gpu/rutabaga_gfx.git "$SRC/rutabaga_gfx"
fi
git -C "$SRC/rutabaga_gfx" checkout -q "$RUTABAGA_REV"
git -C "$SRC/rutabaga_gfx" apply --check "$REPO/patches/0001-kqprobe-cdylib-server-JNI-entry-EOF-fix-AHB-stub-abs.patch" 2>/dev/null \
  && git -C "$SRC/rutabaga_gfx" apply "$REPO/patches/0001-kqprobe-cdylib-server-JNI-entry-EOF-fix-AHB-stub-abs.patch" \
  || echo "(rutabaga patch already applied)"

mkdir -p "$SRC/rutabaga_gfx/.cargo"
cat > "$SRC/rutabaga_gfx/.cargo/config.toml" <<CARGO
[target.aarch64-linux-android]
linker = "$LLVM/aarch64-linux-android34-clang"
rustflags = ["-C", "link-arg=-Wl,--allow-shlib-undefined"]
CARGO

# rustup target: rutabaga pins 1.88.0 via rust-toolchain.toml — add the target
# INSIDE the checkout so it lands in the pinned toolchain, not the default one.
( cd "$SRC/rutabaga_gfx" && rustup target add aarch64-linux-android )

( cd "$SRC/rutabaga_gfx" \
  && GFXSTREAM_PATH="$SRC/gfxstream-build/host" \
     cargo build --release -p kumquat_virtio --features gfxstream \
       --target aarch64-linux-android )
cp "$SRC/rutabaga_gfx/target/aarch64-linux-android/release/libkqserver.so" "$OUT/"

# --- 3. libc++_shared (backend NEEDED it) ---------------------------------------
cp "$SYSROOT/usr/lib/aarch64-linux-android/libc++_shared.so" "$OUT/"

echo
echo "artifacts in $OUT:"
ls -la "$OUT"
