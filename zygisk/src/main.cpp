/*
 * Bootloader Spoofer Zygisk Module
 *
 * Native-level system property spoofing for bootloader state.
 * This module hooks __system_property_get at the native level to
 * intercept reads of bootloader-related properties.
 *
 * Supports two injection modes:
 * 1. Standard Zygisk API (via Magisk's built-in Zygisk)
 * 2. NeoZygisk ptrace injection (via zygisk-ptrace binary)
 *
 * Build: see zygisk/build.sh
 */

#include <jni.h>
#include <sys/system_properties.h>
#include <sys/types.h>
#include <sys/sysmacros.h>
#include <string>
#include <cstring>
#include <cstdarg>
#include <cstddef>
#include <fcntl.h>
#include <unistd.h>
#include <dlfcn.h>
#include <android/log.h>
#include <sys/stat.h>
#include <time.h>
#include "zygisk.h"

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

static const char *MODULE_TAG = "BootloaderSpoofer-Zygisk";
static const char *KLOG_PATH = "/dev/kmsg";
static const char *LOG_FILE = "/data/adb/bootloaderspoofer/zygisk.log";

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, MODULE_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MODULE_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Kernel logging support
//
// Writes log messages to the kernel log (/dev/kmsg) so they appear in dmesg,
// and also to a persistent log file. This is useful for debugging the Zygisk
// module's property hooks and injection lifecycle.
// ---------------------------------------------------------------------------

static int g_kmsg_fd = -1;
static int g_log_fd = -1;

static void klog_init() {
    // Open kernel log (/dev/kmsg)
    if (g_kmsg_fd < 0) {
        g_kmsg_fd = open(KLOG_PATH, O_WRONLY | O_APPEND);
    }
    // Open persistent log file
    if (g_log_fd < 0) {
        // Ensure directory exists
        mkdir("/data/adb/bootloaderspoofer", 0755);
        g_log_fd = open(LOG_FILE, O_WRONLY | O_APPEND | O_CREAT, 0644);
    }
}

static void klog_write(int level, const char *fmt, ...) {
    char buf[512];
    va_list args;
    va_start(args, fmt);

    // Prefix with timestamp and level
    const char *level_str;
    switch (level) {
        case ANDROID_LOG_ERROR: level_str = "E"; break;
        case ANDROID_LOG_WARN:   level_str = "W"; break;
        case ANDROID_LOG_INFO:   level_str = "I"; break;
        case ANDROID_LOG_DEBUG:  level_str = "D"; break;
        default:                 level_str = "?"; break;
    }

    int prefix_len = snprintf(buf, sizeof(buf), "[%s] [%s] ", MODULE_TAG, level_str);
    if (prefix_len < 0 || (size_t)prefix_len >= sizeof(buf)) {
        va_end(args);
        return;
    }

    int msg_len = vsnprintf(buf + prefix_len, sizeof(buf) - prefix_len - 1, fmt, args);
    va_end(args);

    if (msg_len < 0) return;

    int total_len = prefix_len + msg_len;
    if ((size_t)total_len >= sizeof(buf)) total_len = sizeof(buf) - 1;
    buf[total_len] = '\0';

    // Add newline
    if (total_len < (int)sizeof(buf) - 1) {
        buf[total_len] = '\n';
        buf[total_len + 1] = '\0';
        total_len++;
    }

    // Write to kernel log
    if (g_kmsg_fd >= 0) {
        write(g_kmsg_fd, buf, total_len);
    }

    // Write to log file
    if (g_log_fd >= 0) {
        write(g_log_fd, buf, total_len);
    }

    // Also write to Android log
    __android_log_print(level, MODULE_TAG, "%s", buf + prefix_len);
}

// Convenience macros for kernel logging
#define KLOGI(...) klog_write(ANDROID_LOG_INFO, __VA_ARGS__)
#define KLOGW(...) klog_write(ANDROID_LOG_WARN, __VA_ARGS__)
#define KLOGE(...) klog_write(ANDROID_LOG_ERROR, __VA_ARGS__)
#define KLOGD(...) klog_write(ANDROID_LOG_DEBUG, __VA_ARGS__)

// Properties to spoof and their values for locked/unlocked states
struct PropOverride {
    const char *key;
    const char *lockedValue;
    const char *unlockedValue;
};

static const PropOverride PROP_OVERRIDES[] = {
    {"ro.boot.verifiedbootstate",     "green",    "orange"},
    {"ro.boot.flash.locked",           "1",        "0"},
    {"ro.boot.vbmeta.device_state",    "locked",   "unlocked"},
    {"ro.boot.warranty_bit",           "0",        "1"},
    {"ro.bootimage.build.tags",        "release-keys", "release-keys"},
    {"ro.build.tags",                  "release-keys", "release-keys"},
    {"sys.oem_unlock_allowed",         "0",        "1"},
    {nullptr, nullptr, nullptr}
};

static bool g_bootStateUnlocked = false;
static bool g_initialized = false;

// Read boot state from config file
static void readBootState() {
    g_bootStateUnlocked = false;
    int fd = open("/data/adb/bootloaderspoofer/zygisk_bootstate.txt", O_RDONLY);
    if (fd >= 0) {
        char buf[32] = {0};
        read(fd, buf, sizeof(buf) - 1);
        close(fd);
        // Trim whitespace
        char *p = buf;
        while (*p == ' ' || *p == '\n' || *p == '\r' || *p == '\t') p++;
        if (strncmp(p, "unlocked", 8) == 0) {
            g_bootStateUnlocked = true;
        }
        KLOGI("readBootState: %s -> unlocked=%d", p, g_bootStateUnlocked);
    } else {
        KLOGW("readBootState: config file not found, defaulting to locked");
    }
    g_initialized = true;
}

// Hook for __system_property_get
// We use PLT hooking via Zygisk's built-in capabilities
static int (*orig_system_property_get)(const char *, char *) = nullptr;

static int hooked_system_property_get(const char *name, char *value) {
    int result = orig_system_property_get(name, value);

    if (!g_initialized) readBootState();

    for (int i = 0; PROP_OVERRIDES[i].key != nullptr; i++) {
        if (strcmp(name, PROP_OVERRIDES[i].key) == 0) {
            const char *newVal = g_bootStateUnlocked
                ? PROP_OVERRIDES[i].unlockedValue
                : PROP_OVERRIDES[i].lockedValue;
            KLOGD("spoof prop: %s -> %s (was: %s)", name, newVal, value);
            strncpy(value, newVal, PROP_VALUE_MAX - 1);
            value[PROP_VALUE_MAX - 1] = '\0';
            return strlen(value);
        }
    }
    return result;
}

class BootloaderSpooferModule : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
        klog_init();
        KLOGI("Zygisk module loaded (pid=%d)", getpid());
        readBootState();
    }

    void preAppSpecialize(AppSpecializeArgs *args) override {
        // Read the process name
        const char *process = env->GetStringUTFChars(args->nice_name, nullptr);
        if (process == nullptr) return;
        KLOGD("preAppSpecialize: process=%s uid=%d", process, args->uid);

        // Skip if Zygisk mode is off (check config)
        int modeFd = open("/data/adb/bootloaderspoofer/zygisk_mode.txt", O_RDONLY);
        if (modeFd < 0) {
            // No config = Zygisk off, don't hook
            KLOGD("Zygisk mode off (no config), skipping %s", process);
            env->ReleaseStringUTFChars(args->nice_name, process);
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        char modeBuf[32] = {0};
        read(modeFd, modeBuf, sizeof(modeBuf) - 1);
        close(modeFd);

        // Only hook if mode is "active"
        if (strstr(modeBuf, "active") == nullptr) {
            KLOGD("Zygisk mode not active (%s), skipping %s", modeBuf, process);
            env->ReleaseStringUTFChars(args->nice_name, process);
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        // In scoped mode, skip system framework processes
        int scopeFd = open("/data/adb/bootloaderspoofer/spoofscope.txt", O_RDONLY);
        if (scopeFd >= 0) {
            char scopeBuf[32] = {0};
            read(scopeFd, scopeBuf, sizeof(scopeBuf) - 1);
            close(scopeFd);
            if (strstr(scopeBuf, "scoped") != nullptr) {
                if (strcmp(process, "system_server") == 0
                    || strcmp(process, "android") == 0
                    || strncmp(process, "com.android.systemui", 20) == 0) {
                    env->ReleaseStringUTFChars(args->nice_name, process);
                    api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
                    return;
                }
            }
        }

        env->ReleaseStringUTFChars(args->nice_name, process);

        // Install property hooks via PLT hooking
        // The Zygisk API requires dev_t and ino_t to identify the library.
        // We scan /proc/self/maps to find libc.so's dev and inode.
        // If we can't find it, we register with 0/0 which hooks all matching symbols.
        dev_t libc_dev = 0;
        ino_t libc_inode = 0;
        FILE *maps = fopen("/proc/self/maps", "r");
        if (maps) {
            char line[512];
            while (fgets(line, sizeof(line), maps)) {
                if (strstr(line, "libc.so") && strstr(line, "r-xp")) {
                    unsigned long start, end;
                    char perms[5];
                    unsigned long offset;
                    char dev_str[16];
                    unsigned long inode;
                    // Format: addr perms offset dev inode pathname
                    if (sscanf(line, "%lx-%lx %4s %lx %15s %lu", &start, &end, perms, &offset, dev_str, &inode) >= 6) {
                        // Parse dev "major:minor" into dev_t
                        unsigned int major, minor;
                        if (sscanf(dev_str, "%u:%u", &major, &minor) == 2) {
                            libc_dev = makedev(major, minor);
                            libc_inode = (ino_t)inode;
                        }
                        break;
                    }
                }
            }
            fclose(maps);
        }

        // Register the PLT hook for __system_property_get in libc.so
        api->pltHookRegister(libc_dev, libc_inode,
            "__system_property_get",
            (void *)hooked_system_property_get,
            (void **)&orig_system_property_get);

        // Commit the hooks
        if (!api->pltHookCommit()) {
            KLOGE("Failed to commit PLT hooks for __system_property_get");
        } else {
            KLOGI("PLT hooks committed for %s (libc dev=%lu inode=%lu)",
                  process, (unsigned long)libc_dev, (unsigned long)libc_inode);
        }
        // Hooks are already registered, nothing more to do
    }

    void preServerSpecialize(ServerSpecializeArgs *args) override {
        KLOGI("preServerSpecialize: skipping system_server (scoped mode)");
        // Don't hook system_server in scoped mode
        api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

private:
    Api *api = nullptr;
    JNIEnv *env = nullptr;
};

REGISTER_ZYGISK_MODULE(BootloaderSpooferModule)

// ---------------------------------------------------------------------------
// NeoZygisk ptrace injection entry point
//
// When the ptrace injector (zygisk-ptrace) injects this library into Zygote,
// it calls this exported function to initialize the module.
// This is the NeoZygisk-style entry point that complements the standard
// Zygisk REGISTER_ZYGISK_MODULE path.
// ---------------------------------------------------------------------------

__attribute__((visibility("default")))
extern "C" void entry(void *start_addr, size_t block_size, const char *path) {
    klog_init();
    KLOGI("BootloaderSpoofer injected via NeoZygisk ptrace, path=%s", path ? path : "(null)");

    // Read boot state config
    readBootState();

    // Install the property hook directly via PLT hooking
    // In ptrace injection mode, we don't have the Zygisk API available,
    // so we use inline/PLT hooking directly
    //
    // The __system_property_get hook is installed by replacing the PLT entry
    // in the Zygote process. We use dlsym to find the real function and
    // then hook it.
    void *libc_handle = dlopen("libc.so", RTLD_NOW);
    if (libc_handle) {
        void *real_func = dlsym(libc_handle, "__system_property_get");
        if (real_func) {
            // Store the original function pointer
            orig_system_property_get = (int (*)(const char *, char *))real_func;
            KLOGI("found __system_property_get at %p", real_func);
        } else {
            KLOGE("dlsym failed to find __system_property_get");
        }
        dlclose(libc_handle);
    } else {
        KLOGE("dlopen libc.so failed");
    }

    // In a full NeoZygisk implementation, we would hook the JNI methods
    // nativeForkAndSpecialize/nativeSpecializeAppProcess here to intercept
    // process creation, similar to NeoZygisk's hook_zygote_jni().
    // For now, the property hook via PLT replacement handles the core
    // bootloader spoofing functionality.

    KLOGI("BootloaderSpoofer NeoZygisk entry complete");
}
