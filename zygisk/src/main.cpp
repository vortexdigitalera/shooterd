/*
 * Bootloader Spoofer Zygisk Module
 *
 * Native-level system property spoofing for bootloader state.
 * This module hooks __system_property_get at the native level to
 * intercept reads of bootloader-related properties.
 *
 * Build: see zygisk/build.sh
 */

#include <jni.h>
#include <sys/system_properties.h>
#include <string>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include "zygisk.h"

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

static const char *MODULE_TAG = "BootloaderSpoofer-Zygisk";

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
        readBootState();
    }

    void preAppSpecialize(AppSpecializeArgs *args) override {
        // Read the process name
        const char *process = env->GetStringUTFChars(args->nice_name, nullptr);
        if (process == nullptr) return;

        // Skip if Zygisk mode is off (check config)
        int modeFd = open("/data/adb/bootloaderspoofer/zygisk_mode.txt", O_RDONLY);
        if (modeFd < 0) {
            // No config = Zygisk off, don't hook
            env->ReleaseStringUTFChars(args->nice_name, process);
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        char modeBuf[32] = {0};
        read(modeFd, modeBuf, sizeof(modeBuf) - 1);
        close(modeFd);

        // Only hook if mode is "active"
        if (strstr(modeBuf, "active") == nullptr) {
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
        // Zygisk provides a clean way to do this
        api->pltHookRegister(args->uid, "libc.so",
            "__system_property_get",
            (void *)hooked_system_property_get,
            (void **)&orig_system_property_get);
    }

    void postAppSpecialize(const AppSpecializeArgs *args) override {
        // Hooks are already registered, nothing more to do
    }

    void preServerSpecialize(ServerSpecializeArgs *args) override {
        // Don't hook system_server in scoped mode
        api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

private:
    Api *api = nullptr;
    JNIEnv *env = nullptr;
};

REGISTER_ZYGISK_MODULE(BootloaderSpooferModule)
