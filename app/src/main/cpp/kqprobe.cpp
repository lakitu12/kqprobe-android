// kqprobe.cpp — app-domain Vulkan capability probe
// 1) libvulkan.so (public loader -> vendor ICD) enumerate physical devices
// 2) direct vendor ICD dlopen (expected to be blocked in app ns; diagnostic only)
// 3) gfxstream bionic backend dlopen (the kumquat host server core)
// 4) optional AF_UNIX connect probe against the kumquat server
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <string>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <vector>
#include <vulkan/vulkan.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "kqprobe", __VA_ARGS__)

static std::string g_out;
static void add(const char* fmt, ...) {
    char buf[1024];
    va_list ap; va_start(ap, fmt); vsnprintf(buf, sizeof buf, fmt, ap); va_end(ap);
    g_out += buf; g_out += "\n";
    LOGI("%s", buf);
}

static void probe_loader() {
    void* h = dlopen("libvulkan.so", RTLD_NOW);
    if (!h) { add("[loader] dlopen libvulkan.so FAIL: %s", dlerror()); return; }
    auto gipa = (PFN_vkGetInstanceProcAddr)dlsym(h, "vkGetInstanceProcAddr");
    if (!gipa) { add("[loader] no vkGetInstanceProcAddr"); return; }
    add("[loader] dlopen ok, GIPA=%p", (void*)gipa);

    uint32_t ver = 0;
    auto enumVer = (PFN_vkEnumerateInstanceVersion)gipa(nullptr, "vkEnumerateInstanceVersion");
    if (enumVer) { enumVer(&ver); add("[loader] vulkan %u.%u.%u", VK_VERSION_MAJOR(ver), VK_VERSION_MINOR(ver), VK_VERSION_PATCH(ver)); }

    const char* exts[] = {"VK_KHR_surface", "VK_KHR_xlib_surface", "VK_EXT_headless_surface"};
    uint32_t cnt = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &cnt, nullptr);
    std::vector<VkExtensionProperties> props(cnt);
    vkEnumerateInstanceExtensionProperties(nullptr, &cnt, props.data());
    add("[loader] %u instance extensions", cnt);
    for (auto& e : exts) {
        bool found = false;
        for (auto& p : props) if (!strcmp(p.extensionName, e)) found = true;
        add("[loader]   %s: %s", e, found ? "YES" : "no");
    }

    VkInstanceCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    VkInstance inst = nullptr;
    VkResult r = vkCreateInstance(&ci, nullptr, &inst);
    if (r != VK_SUCCESS) { add("[loader] vkCreateInstance = %d", r); return; }
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(inst, &n, nullptr);
    add("[loader] %u physical devices", n);
    std::vector<VkPhysicalDevice> devs(n);
    if (n) {
        vkEnumeratePhysicalDevices(inst, &n, devs.data());
        for (auto d : devs) {
            VkPhysicalDeviceProperties p;
            vkGetPhysicalDeviceProperties(d, &p);
            add("[loader]   device: %s (api %u.%u.%u, type %d)",
                p.deviceName, VK_VERSION_MAJOR(p.apiVersion),
                VK_VERSION_MINOR(p.apiVersion), VK_VERSION_PATCH(p.apiVersion), p.deviceType);
        }
    }
    vkDestroyInstance(inst, nullptr);
}

static void probe_direct_mali() {
    void* h = dlopen("/vendor/lib64/hw/mt6991/vulkan.mali.so", RTLD_NOW);
    add("[mali-direct] dlopen: %s", h ? "ok" : dlerror());
    if (h) {
        auto gipa = (PFN_vkGetInstanceProcAddr)dlsym(h, "vkGetInstanceProcAddr");
        add("[mali-direct] vkGetInstanceProcAddr = %p", (void*)gipa);
    }
}

static void probe_backend() {
    // bundled in the APK's native lib dir (same namespace as libkqserver.so)
    void* h = dlopen("libgfxstream_backend.so", RTLD_NOW);
    add("[backend] dlopen: %s", h ? "ok" : dlerror());
    if (!h) return;
    auto initFn = dlsym(h, "stream_renderer_init");
    add("[backend] stream_renderer_init=%p", initFn);
}

static void probe_server_socket(const char* path) {
    int fd = socket(AF_UNIX, SOCK_SEQPACKET, 0);
    if (fd < 0) { add("[server] socket() fail"); return; }
    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    socklen_t sl;
    if (path[0] == '@') {
        // abstract namespace: sun_path[0]=0, name follows
        size_t nl = strlen(path + 1);
        if (nl > sizeof(addr.sun_path) - 1) nl = sizeof(addr.sun_path) - 1;
        addr.sun_path[0] = '\0';
        memcpy(addr.sun_path + 1, path + 1, nl);
        sl = offsetof(struct sockaddr_un, sun_path) + 1 + nl;
    } else {
        strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
        sl = sizeof(addr);
    }
    int r = connect(fd, (sockaddr*)&addr, sl);
    add("[server] connect %s: %s", path, r == 0 ? "OK (server listening)" : strerror(errno));
    close(fd);
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_lakitu_kqprobe_MainActivity_probeVulkan(JNIEnv* env, jobject) {
    g_out.clear();
    probe_loader();
    probe_direct_mali();
    probe_backend();
    return env->NewStringUTF(g_out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_lakitu_kqprobe_MainActivity_probeSocket(JNIEnv* env, jobject, jstring jpath) {
    g_out.clear();
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    probe_server_socket(path);
    env->ReleaseStringUTFChars(jpath, path);
    return env->NewStringUTF(g_out.c_str());
}
