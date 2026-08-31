# kqprobe — gfxstream GPU bridge for Android LXC containers

Android app that runs a [kumquat](https://github.com/magma-gpu/rutabaga_gfx)
virtio-gpu server **inside the app domain** and exposes it over an AF_UNIX
socket, so glibc containers (e.g. [DroidSpaces](https://github.com/ravindu644/Droidspaces-OSS))
can use the device's **real Vulkan GPU** through the Mesa gfxstream guest ICD.

Verified on Redmi Turbo 5 Max (dash, MT6991Z): container `vulkaninfo` reports
`deviceName = Virtio-GPU GFXStream (Mali-G925-Immortalis MC12)`.

## Why the app domain

Vendor Vulkan stacks (`vulkan.mali.so` and friends) are only resolvable through
the system `libvulkan.so` loader in an *app* linker namespace — a root/shell
daemon cannot enumerate them. So the server must live in an app process.
The gfxstream host backend (`libgfxstream_backend.so`, bionic build) is
dlopen'd by the server inside that same app domain.

```
container (glibc)                     Android host (bionic)
  guest ICD (patched Mesa)  ──AF_UNIX──▶  KumquatService (foreground app)
  VIRTGPU_KUMQUAT=1          socket          libkqserver.so (cdylib)
  KUMQUAT_GPU_SOCKET=...                     libgfxstream_backend.so
                                               └─ libvulkan.so → vulkan.mali.so
```

## Build

Requirements: Android SDK (platform 36, build-tools), NDK r26+, Rust (rustup,
target `aarch64-linux-android`), meson ≥1.0, ninja, JDK 17.

The APK needs three native libraries that are **not checked in** (large /
build-from-source):

| file | what |
|---|---|
| `libgfxstream_backend.so` | gfxstream host backend, bionic aarch64 |
| `libkqserver.so` | this repo's kumquat server cdylib (JNI) |
| `libc++_shared.so` | NDK sysroot runtime required by the backend |

### 1. Native deps

```bash
ANDROID_NDK_HOME=~/Android/Sdk/ndk/<ver> bash scripts/build-native.sh
```

Pins upstream revisions (`gfxstream@5b92790`, `rutabaga_gfx@13cde31`) and
applies `patches/*` automatically; outputs land in
`app/src/main/jniLibs/arm64-v8a/` (gitignored).

What the patch set adds to upstream:

* `kumquat/server`: `[lib] crate-type=["cdylib"]` + JNI entry
  (`Java_me_lakitu_kqprobe_MainActivity_nativeStart`) with `jni` crate
* kumquat EOF-handling fix (dead-connection CPU spin on peer kill)
* `gfxstream.rs` AHB export stubbed (upstream references AOSP-internal
  `nativewindow` crate, unavailable on crates.io)
* `magma-gpu-rs` tube.rs: leading `@` → abstract AF_UNIX name, both bind and
  connect (cfg `any(linux, android)`)
* gfxstream `host/native_window/meson.build`: `system == 'android'` branch

### 2. APK

```bash
./gradlew :app:assembleDebug      # debug (auto-signed with your debug keystore)
./gradlew :app:assembleRelease    # unsigned unless keystore.properties exists
```

`keystore.properties` (`storeFile/storePassword/keyAlias/keyPassword`) or env
`KQPROBE_KEYBASE64` + `KQPROBE_KSPASS` switches release to real signing.

GitHub Actions (`.github/workflows/build.yml`) does both steps for you: the
`native` job runs `build-native.sh`, the `apk` job assembles, signs (CI debug
key by default; add the two secrets for your own key) and uploads
`kqprobe-release.apk`.

## Install & run

```bash
adb install -t kqprobe-release.apk
```

* Open **KQProbe** → toggle **"GPU bridge daemon"** on: a foreground service
  (`specialUse`) starts the kumquat server and keeps it alive; the toggle
  persists and `BOOT_COMPLETED` re-arms it after reboot.
* Socket mode (read at service start):
  * **abstract** `@kumquat-gpu-0` — active when `/data/local/tmp/kq-abstract`
    exists (needs root to create). Zero bind-mounts; works when the container
    shares the host network namespace (`net_mode=host`).
  * **file** `/data/data/me.lakitu.kqprobe/kumquat-gpu-0` — bind into the
    container with DroidSpaces `container.config`:
    `bind_mounts=/data/data/me.lakitu.kqprobe/kumquat-gpu-0:/tmp/kumquat-gpu-0`
    (re-`droidspaces restart` after app restarts; the socket inode changes).
* In-container test (needs the patched guest ICD, see below):
  ```bash
  unset DISPLAY
  VIRTGPU_KUMQUAT=1 KUMQUAT_GPU_SOCKET=@kumquat-gpu-0 \
  VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/gfxstream_vk_icd.json vulkaninfo --summary
  ```

## Guest side

The container needs a Mesa Vulkan ICD built with `-Dvulkan-drivers=gfxstream
-Dvirtgpu_kumquat=true` **plus** the `KUMQUAT_GPU_SOCKET` / abstract-socket
patches. CI repo: [`lakitu12/mesa-gfxstream-container`](https://github.com/lakitu12/mesa-gfxstream-container)
(mesa 26.2.1, xlib/headless extension whitelist + socket override patches).

## Status & known limitations

* udmabuf path disabled (`VulkanAllocateHostVisibleAsUdmabuf:disabled`): the
  app domain can't open root-only `/dev/udmabuf`; blob memory falls back to
  memfd (works, no zero-copy).
* AHB (AHardwareBuffer) export is stubbed — needs the AOSP `nativewindow` crate.
* On-screen (X11/Wayland) swapchain paths untested on the hardware link;
  headless Vulkan works today.

## Layout

```
app/src/main/cpp/          JNI probe sources (libkqprobe.so, built by gradle)
app/src/main/java/         Activity / foreground service / boot receiver
patches/                   upstream diffs consumed by scripts/build-native.sh
scripts/build-native.sh    native dependency builder
.github/workflows/         native + APK CI
```

Licensed under the licenses of the upstreams involved (BSD-3 rutabaga_gfx,
Apache-2.0 gfxstream). This repo's sources: Apache-2.0.
