/*
 * BootloaderSpoofer NeoZygisk-style ptrace injector
 *
 * Based on NeoZygisk by JingMatrix (https://github.com/JingMatrix/NeoZygisk)
 * Implements ptrace-based Zygote injection for APatch/KernelSU/Magisk.
 *
 * This binary monitors the Zygote process and injects libbootloaderspoofer.so
 * into it using ptrace, providing Zygisk API support without Magisk's built-in Zygisk.
 *
 * Build: see zygisk/build.sh
 * Usage: zygisk-ptrace64 monitor | trace <pid> [--restart]
 */

#include <dlfcn.h>
#include <dirent.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cinttypes>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>

#define LOG_TAG "BootloaderSpoofer-Ptrace"
#define LOGI(fmt, ...) fprintf(stderr, "I [" LOG_TAG "] " fmt "\n", ##__VA_ARGS__)
#define LOGW(fmt, ...) fprintf(stderr, "W [" LOG_TAG "] " fmt "\n", ##__VA_ARGS__)
#define LOGE(fmt, ...) fprintf(stderr, "E [" LOG_TAG "] " fmt "\n", ##__VA_ARGS__)
#define PLOGE(fmt, ...) fprintf(stderr, "E [" LOG_TAG "] " fmt ": %s\n", ##__VA_ARGS__, strerror(errno))
#define LOGV(fmt, ...)  // verbose off by default

#if defined(__LP64__)
#define LP_SELECT(lp32, lp64) lp64
#define ZYGOTE_PATH "/system/bin/app_process64"
#define TRACER_NAME "zygisk-ptrace64"
#define LIB_DIR "lib64"
#else
#define LP_SELECT(lp32, lp64) lp32
#define ZYGOTE_PATH "/system/bin/app_process32"
#define TRACER_NAME "zygisk-ptrace32"
#define LIB_DIR "lib"
#endif

// Work directory for temporary files
static std::string g_tmpPath = "/dev/zygisk_bs";

// ---------------------------------------------------------------------------
// Process memory read/write helpers
// ---------------------------------------------------------------------------

static bool read_proc(pid_t pid, uintptr_t addr, void *buf, size_t len) {
    struct iovec local = {buf, len};
    struct iovec remote = {reinterpret_cast<void *>(addr), len};
    ssize_t n = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    if (n != (ssize_t)len) {
        PLOGE("process_vm_readv");
        return false;
    }
    return true;
}

static bool write_proc(pid_t pid, uintptr_t addr, const void *buf, size_t len) {
    struct iovec local = {const_cast<void *>(buf), len};
    struct iovec remote = {reinterpret_cast<void *>(addr), len};
    ssize_t n = process_vm_writev(pid, &local, 1, &remote, 1, 0);
    if (n != (ssize_t)len) {
        PLOGE("process_vm_writev");
        return false;
    }
    return true;
}

#if defined(__aarch64__)
#include <sys/user.h>
// On aarch64, user_regs_struct is already defined in sys/user.h as struct user_pt_regs
// We use it directly via the existing definition
#define REG_IP pc
#define REG_SP sp
#define REG_LR regs[30]
#elif defined(__x86_64__)
#include <sys/user.h>
using user_regs_struct = struct user_regs_struct;
#define REG_IP rip
#define REG_SP rsp
#elif defined(__i386__)
#include <sys/user.h>
using user_regs_struct = struct user_regs_struct;
#define REG_IP eip
#define REG_SP esp
#elif defined(__arm__)
#include <sys/user.h>
using user_regs_struct = struct user_regs;
#define REG_IP uregs[15]
#define REG_SP uregs[13]
#define REG_LR uregs[14]
#endif

static bool get_regs(pid_t pid, user_regs_struct &regs) {
    struct iovec iov = {&regs, sizeof(regs)};
    if (ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS, &iov) == -1) {
        PLOGE("PTRACE_GETREGSET");
        return false;
    }
    return true;
}

static bool set_regs(pid_t pid, const user_regs_struct &regs) {
    struct iovec iov = {const_cast<user_regs_struct *>(&regs), sizeof(regs)};
    if (ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS, &iov) == -1) {
        PLOGE("PTRACE_SETREGSET");
        return false;
    }
    return true;
}

// ---------------------------------------------------------------------------
// Remote function call via ptrace
// ---------------------------------------------------------------------------

static uintptr_t push_string(pid_t pid, user_regs_struct &regs, const char *str) {
    size_t len = strlen(str) + 1;
    // Align SP down to 16 bytes
    regs.REG_SP -= len;
    regs.REG_SP &= ~0xFUL;
    uintptr_t addr = regs.REG_SP;
    if (!write_proc(pid, addr, str, len)) {
        LOGE("push_string: write failed");
        return 0;
    }
    return addr;
}

static bool remote_call(pid_t pid, user_regs_struct &regs, uintptr_t func_addr,
                        uintptr_t return_addr, const std::vector<uintptr_t> &args) {
    regs.REG_IP = func_addr;

#if defined(__aarch64__)
    // AArch64: x0-x7 for first 8 args, rest on stack
    for (size_t i = 0; i < args.size() && i < 8; i++) {
        regs.regs[i] = args[i];
    }
    // Set LR (return address)
    regs.regs[30] = return_addr;
    // Stack must be 16-byte aligned
    regs.REG_SP &= ~0xFUL;
#elif defined(__x86_64__)
    // x86_64: rdi, rsi, rdx, rcx, r8, r9 for first 6 args
    if (args.size() > 0) regs.rdi = args[0];
    if (args.size() > 1) regs.rsi = args[1];
    if (args.size() > 2) regs.rdx = args[2];
    if (args.size() > 3) regs.rcx = args[3];
    if (args.size() > 4) regs.r8 = args[4];
    if (args.size() > 5) regs.r9 = args[5];
    // Push remaining args on stack (right to left)
    for (size_t i = 6; i < args.size(); i++) {
        regs.REG_SP -= 8;
        write_proc(pid, regs.REG_SP, &args[i], sizeof(uintptr_t));
    }
    // Push return address
    regs.REG_SP -= 8;
    write_proc(pid, regs.REG_SP, &return_addr, sizeof(uintptr_t));
#elif defined(__arm__)
    // ARM: r0-r3 for first 4 args, rest on stack
    for (size_t i = 0; i < args.size() && i < 4; i++) {
        regs.uregs[i] = args[i];
    }
    // Push remaining args on stack (right to left)
    for (size_t i = 4; i < args.size(); i++) {
        regs.REG_SP -= 4;
        write_proc(pid, regs.REG_SP, &args[i], sizeof(uintptr_t));
    }
    // Set LR
    regs.REG_LR = return_addr;
#elif defined(__i386__)
    // x86: all args on stack (right to left)
    for (size_t i = args.size(); i > 0; i--) {
        regs.REG_SP -= 4;
        write_proc(pid, regs.REG_SP, &args[i - 1], sizeof(uintptr_t));
    }
    // Push return address
    regs.REG_SP -= 4;
    write_proc(pid, regs.REG_SP, &return_addr, sizeof(uintptr_t));
#endif

    if (!set_regs(pid, regs)) return false;

    // Resume and wait for SIGSEGV (hitting the return address trap)
    if (ptrace(PTRACE_CONT, pid, 0, 0) == -1) {
        PLOGE("PTRACE_CONT in remote_call");
        return false;
    }

    int status;
    waitpid(pid, &status, __WALL);

    if (WIFEXITED(status) || WIFSIGNALED(status)) {
        LOGE("process died during remote_call");
        return false;
    }

    if (!WIFSTOPPED(status)) {
        LOGE("unexpected status in remote_call: 0x%x", status);
        return false;
    }

    int sig = WSTOPSIG(status);
    if (sig != SIGSEGV) {
        LOGE("expected SIGSEGV at return trap, got signal %d", sig);
        return false;
    }

    // Read back registers (result is in x0/rax/r0)
    if (!get_regs(pid, regs)) return false;

    return true;
}

// ---------------------------------------------------------------------------
// Find remote function addresses
// ---------------------------------------------------------------------------

struct RemoteAddrs {
    uintptr_t dlopen;
    uintptr_t dlsym;
    uintptr_t libc_return;  // An invalid address to use as return trap
};

// Find the address of a symbol in the remote process by scanning /proc/pid/maps
static uintptr_t find_remote_symbol(pid_t pid, const char *lib_name, const char *sym_name) {
    // Read maps to find the base address of the library
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    FILE *f = fopen(maps_path, "r");
    if (!f) {
        PLOGE("open %s", maps_path);
        return 0;
    }

    uintptr_t base_addr = 0;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, lib_name) && strstr(line, "r-xp")) {
            sscanf(line, "%" SCNxPTR, &base_addr);
            break;
        }
    }
    fclose(f);

    if (base_addr == 0) {
        LOGE("could not find %s in process %d", lib_name, pid);
        return 0;
    }

    // Find the symbol locally in the same library
    void *local_handle = dlopen(lib_name, RTLD_NOW);
    if (!local_handle) {
        LOGE("could not dlopen %s locally: %s", lib_name, dlerror());
        return 0;
    }

    void *local_sym = dlsym(local_handle, sym_name);
    if (!local_sym) {
        LOGE("could not find %s in %s: %s", sym_name, lib_name, dlerror());
        dlclose(local_handle);
        return 0;
    }

    // Find local base address
    uintptr_t local_base = 0;
    f = fopen("/proc/self/maps", "r");
    if (f) {
        while (fgets(line, sizeof(line), f)) {
            if (strstr(line, lib_name) && strstr(line, "r-xp")) {
                sscanf(line, "%" SCNxPTR, &local_base);
                break;
            }
        }
        fclose(f);
    }

    dlclose(local_handle);

    if (local_base == 0) {
        LOGE("could not find local base for %s", lib_name);
        return 0;
    }

    // Calculate remote address
    uintptr_t remote_addr = base_addr + ((uintptr_t)local_sym - local_base);
    LOGV("remote %s@%s: local=%p local_base=0x%" PRIxPTR " remote_base=0x%" PRIxPTR " remote=0x%" PRIxPTR,
         sym_name, lib_name, local_sym, local_base, base_addr, remote_addr);
    return remote_addr;
}

// ---------------------------------------------------------------------------
// Core injection: inject a shared library into a process at its entry point
// Based on NeoZygisk's inject_on_main()
// ---------------------------------------------------------------------------

static bool inject_on_main(pid_t pid, const char *lib_path) {
    LOGI("starting library injection for PID: %d, library: %s", pid, lib_path);

    user_regs_struct regs{}, backup{};
    if (!get_regs(pid, regs)) {
        LOGE("failed to get registers for PID %d", pid);
        return false;
    }
    backup = regs;

    // Parse the Kernel Argument Block to find AT_ENTRY
    // The stack pointer at process startup points to argc, then argv, envp, auxv
    uintptr_t sp = regs.REG_SP;

    // Read argc
    long argc;
    if (!read_proc(pid, sp, &argc, sizeof(argc))) {
        LOGE("failed to read argc");
        return false;
    }

    // Skip argc + argv pointers + null + envp pointers + null to reach auxv
    uintptr_t auxv = sp + sizeof(long) * (1 + argc + 1);
    // Read argv pointers to find end
    for (long i = 0; i <= argc; i++) {
        long val;
        read_proc(pid, sp + sizeof(long) * (1 + i), &val, sizeof(val));
        if (val == 0 && i == argc) break;
    }
    // Read envp to skip it
    uintptr_t envp_start = sp + sizeof(long) * (1 + argc + 1);
    uintptr_t auxv_start = envp_start;
    while (true) {
        long val;
        read_proc(pid, auxv_start, &val, sizeof(val));
        if (val == 0) {
            auxv_start += sizeof(long);
            break;
        }
        auxv_start += sizeof(long);
    }

    // Now scan auxv for AT_ENTRY
    uintptr_t entry_addr = 0;
    uintptr_t addr_of_entry_addr = 0;
    uintptr_t v = auxv_start;
    while (true) {
        ElfW(auxv_t) buf;
        read_proc(pid, v, &buf, sizeof(buf));
        if (buf.a_type == AT_NULL) break;
        if (buf.a_type == AT_ENTRY) {
            entry_addr = (uintptr_t)buf.a_un.a_val;
            addr_of_entry_addr = v + offsetof(ElfW(auxv_t), a_un);
            break;
        }
        v += sizeof(ElfW(auxv_t));
    }

    if (entry_addr == 0) {
        LOGE("failed to find AT_ENTRY for PID %d", pid);
        return false;
    }
    LOGI("found program entry point at 0x%" PRIxPTR, entry_addr);

    // Hijack the entry point with an invalid address to cause SIGSEGV
    uintptr_t break_addr = (-0x05ec1cff & ~1UL) | (entry_addr & 1);
    if (!write_proc(pid, addr_of_entry_addr, &break_addr, sizeof(break_addr))) {
        LOGE("failed to hijack entry point");
        return false;
    }

    // Resume and wait for SIGSEGV
    if (ptrace(PTRACE_CONT, pid, 0, 0) == -1) {
        PLOGE("PTRACE_CONT");
        return false;
    }

    int status;
    waitpid(pid, &status, __WALL);

    if (WIFEXITED(status) || WIFSIGNALED(status)) {
        LOGE("process died before hitting trap");
        return false;
    }

    if (!WIFSTOPPED(status) || WSTOPSIG(status) != SIGSEGV) {
        LOGE("expected SIGSEGV at entry trap, got 0x%x", status);
        return false;
    }

    LOGI("successfully intercepted process %d at entry point", pid);

    // Restore original entry point
    if (!write_proc(pid, addr_of_entry_addr, &entry_addr, sizeof(entry_addr))) {
        LOGE("FATAL: failed to restore entry point");
        return false;
    }

    // Read current registers
    if (!get_regs(pid, regs)) {
        LOGE("failed to get regs after trap");
        return false;
    }

    // Find remote dlopen and dlsym
    uintptr_t remote_dlopen = find_remote_symbol(pid, "libdl.so", "dlopen");
    if (remote_dlopen == 0) {
        // On newer Android, dlopen is in libc.so / libdl.so
        remote_dlopen = find_remote_symbol(pid, "libc.so", "dlopen");
    }
    if (remote_dlopen == 0) {
        LOGE("could not find remote dlopen");
        return false;
    }
    LOGI("found remote dlopen at 0x%" PRIxPTR, remote_dlopen);

    uintptr_t remote_dlsym = find_remote_symbol(pid, "libdl.so", "dlsym");
    if (remote_dlsym == 0) {
        remote_dlsym = find_remote_symbol(pid, "libc.so", "dlsym");
    }
    if (remote_dlsym == 0) {
        LOGE("could not find remote dlsym");
        return false;
    }
    LOGI("found remote dlsym at 0x%" PRIxPTR, remote_dlsym);

    // Use an invalid address as return trap for remote calls
    uintptr_t return_trap = 0xdeadbeef;

    // Push the library path string into the remote process
    uintptr_t remote_lib_path = push_string(pid, regs, lib_path);
    if (remote_lib_path == 0) {
        LOGE("failed to push lib path string");
        return false;
    }

    // Call dlopen(lib_path, RTLD_NOW)
    // RTLD_NOW = 2
    std::vector<uintptr_t> args = {remote_lib_path, 2};
    if (!remote_call(pid, regs, remote_dlopen, return_trap, args)) {
        LOGE("remote dlopen call failed");
        return false;
    }

#if defined(__aarch64__)
    uintptr_t handle = regs.regs[0];
#elif defined(__x86_64__)
    uintptr_t handle = regs.rax;
#elif defined(__arm__)
    uintptr_t handle = regs.uregs[0];
#elif defined(__i386__)
    uintptr_t handle = regs.eax;
#endif

    if (handle == 0) {
        LOGE("dlopen returned null handle");
        return false;
    }
    LOGI("dlopen succeeded, handle=0x%" PRIxPTR, handle);

    // Push "entry" symbol name
    uintptr_t remote_sym_name = push_string(pid, regs, "entry");
    if (remote_sym_name == 0) {
        LOGE("failed to push symbol name");
        return false;
    }

    // Call dlsym(handle, "entry")
    args.clear();
    args.push_back(handle);
    args.push_back(remote_sym_name);
    if (!remote_call(pid, regs, remote_dlsym, return_trap, args)) {
        LOGE("remote dlsym call failed");
        return false;
    }

#if defined(__aarch64__)
    uintptr_t entry_fn = regs.regs[0];
#elif defined(__x86_64__)
    uintptr_t entry_fn = regs.rax;
#elif defined(__arm__)
    uintptr_t entry_fn = regs.uregs[0];
#elif defined(__i386__)
    uintptr_t entry_fn = regs.eax;
#endif

    if (entry_fn == 0) {
        LOGE("dlsym could not find 'entry' symbol");
        return false;
    }
    LOGI("found entry function at 0x%" PRIxPTR, entry_fn);

    // Call entry(start_addr, block_size, tmp_path)
    // We pass 0 for start_addr and block_size since we don't track them
    // and the tmp_path for the module to use
    uintptr_t remote_tmp_path = push_string(pid, regs, g_tmpPath.c_str());
    args.clear();
    args.push_back(0);  // start_addr
    args.push_back(0);  // block_size
    args.push_back(remote_tmp_path);
    if (!remote_call(pid, regs, entry_fn, return_trap, args)) {
        LOGE("remote entry() call failed");
        return false;
    }

    LOGI("injection complete, restoring registers");

    // Restore original registers and entry point
    backup.REG_IP = (long)entry_addr;
    if (!set_regs(pid, backup)) {
        LOGE("failed to restore registers");
        return false;
    }

    return true;
}

// ---------------------------------------------------------------------------
// PTRACE_SEIZE / PTRACE_ATTACH helpers
// Based on NeoZygisk's trace_with_seize() and trace_with_attach()
// ---------------------------------------------------------------------------

static bool perform_injection(pid_t pid) {
    std::string lib_path = g_tmpPath + "/" + LIB_DIR + "/libbootloaderspoofer.so";
    return inject_on_main(pid, lib_path.c_str());
}

static bool trace_with_seize(pid_t pid) {
    LOGI("attempting PTRACE_SEIZE on PID %d", pid);

    if (ptrace(PTRACE_SEIZE, pid, 0, PTRACE_O_EXITKILL) == -1) {
        return false;
    }

    int status;
    if (waitpid(pid, &status, __WALL) < 0) {
        PLOGE("waitpid (seize)");
        ptrace(PTRACE_DETACH, pid, 0, 0);
        return false;
    }

    if (WIFSTOPPED(status) && WSTOPSIG(status) == SIGSTOP) {
        if (!perform_injection(pid)) {
            ptrace(PTRACE_DETACH, pid, 0, 0);
            return false;
        }
        // Detach with SIGCONT to resume
        if (ptrace(PTRACE_DETACH, pid, 0, SIGCONT) == -1) {
            PLOGE("PTRACE_DETACH (seize)");
            return false;
        }
        return true;
    }

    ptrace(PTRACE_DETACH, pid, 0, 0);
    return false;
}

static bool trace_with_attach(pid_t pid) {
    LOGI("falling back to PTRACE_ATTACH on PID %d", pid);

    if (ptrace(PTRACE_ATTACH, pid, 0, 0) == -1) {
        PLOGE("PTRACE_ATTACH");
        return false;
    }

    int status;
    if (waitpid(pid, &status, __WALL) < 0) {
        ptrace(PTRACE_DETACH, pid, 0, 0);
        return false;
    }

    if (WIFSTOPPED(status) && WSTOPSIG(status) == SIGSTOP) {
        ptrace(PTRACE_SETOPTIONS, pid, 0, PTRACE_O_EXITKILL);

        if (!perform_injection(pid)) {
            ptrace(PTRACE_DETACH, pid, 0, 0);
            return false;
        }

        return ptrace(PTRACE_DETACH, pid, 0, SIGCONT) != -1;
    }

    ptrace(PTRACE_DETACH, pid, 0, 0);
    return false;
}

static bool trace_zygote(pid_t pid) {
    LOGI("attaching to zygote (PID: %d)", pid);

    if (trace_with_seize(pid)) {
        LOGI("successfully injected via SEIZE");
        return true;
    }

    if (errno == EIO) {
        LOGW("SEIZE failed with EIO, trying ATTACH fallback");
        if (trace_with_attach(pid)) {
            LOGI("successfully injected via ATTACH");
            return true;
        }
    }

    PLOGE("both SEIZE and ATTACH failed");
    return false;
}

// ---------------------------------------------------------------------------
// Zygote monitor: watches for Zygote process and injects when found
// Based on NeoZygisk's AppMonitor
// ---------------------------------------------------------------------------

static pid_t find_zygote_pid() {
    // Scan /proc for the Zygote process
    // Zygote runs as app_process64 or app_process32
    DIR *dir = opendir("/proc");
    if (!dir) {
        PLOGE("opendir /proc");
        return -1;
    }

    pid_t result = -1;
    struct dirent *entry;
    while ((entry = readdir(dir)) != nullptr) {
        // Skip non-numeric entries
        if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;

        pid_t pid = atoi(entry->d_name);
        if (pid <= 1) continue;

        char cmdline_path[64];
        snprintf(cmdline_path, sizeof(cmdline_path), "/proc/%d/cmdline", pid);
        FILE *f = fopen(cmdline_path, "r");
        if (!f) continue;

        char cmdline[256] = {0};
        fgets(cmdline, sizeof(cmdline), f);
        fclose(f);

        if (strcmp(cmdline, ZYGOTE_PATH) == 0 ||
            strcmp(cmdline, "zygote64") == 0 ||
            strcmp(cmdline, "zygote") == 0 ||
            strcmp(cmdline, "zygote32") == 0) {
            result = pid;
            break;
        }
    }
    closedir(dir);
    return result;
}

static void run_monitor() {
    LOGI("BootloaderSpoofer ptrace monitor started");

    // Wait for Zygote to appear
    int retry = 0;
    const int max_retries = 60;  // 60 seconds max
    pid_t zygote_pid = -1;

    while (retry < max_retries) {
        zygote_pid = find_zygote_pid();
        if (zygote_pid > 0) break;
        retry++;
        sleep(1);
    }

    if (zygote_pid <= 0) {
        LOGE("could not find Zygote process after %d retries", max_retries);
        return;
    }

    LOGI("found Zygote at PID %d, injecting...", zygote_pid);

    if (!trace_zygote(zygote_pid)) {
        LOGE("failed to inject into Zygote (PID: %d)", zygote_pid);
        // Kill the zygote to prevent system instability (as NeoZygisk does)
        kill(zygote_pid, SIGKILL);
        return;
    }

    LOGI("BootloaderSpoofer successfully injected into Zygote");
}

// ---------------------------------------------------------------------------
// CLI: monitor | trace <pid> [--restart] | version
// Based on NeoZygisk's main.cpp
// ---------------------------------------------------------------------------

static void print_usage(const char *tool_name) {
    fprintf(stderr, "BootloaderSpoofer Tracer\n");
    fprintf(stderr, "usage: %s monitor | trace <pid> [--restart] | version\n", tool_name);
}

static int handle_trace(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "error: trace command requires a PID\n");
        print_usage(argv[0]);
        return EXIT_FAILURE;
    }

    char *end_ptr;
    errno = 0;
    long pid_val = strtol(argv[2], &end_ptr, 10);
    if (*end_ptr != '\0' || errno != 0 || pid_val <= 0) {
        fprintf(stderr, "error: invalid PID: '%s'\n", argv[2]);
        return EXIT_FAILURE;
    }

    pid_t pid = (pid_t)pid_val;
    printf("preparing to trace PID: %d\n", pid);

    if (argc >= 4 && strcmp(argv[3], "--restart") == 0) {
        printf("zygote restart requested...\n");
        // Trigger zygote restart by killing it
        // The system will respawn it and the monitor will catch it
    }

    if (!trace_zygote(pid)) {
        fprintf(stderr, "error: failed to trace zygote, killing PID %d\n", pid);
        kill(pid, SIGKILL);
        return EXIT_FAILURE;
    }

    printf("successfully attached and injected into PID: %d\n", pid);
    return EXIT_SUCCESS;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        print_usage(argv[0]);
        return EXIT_FAILURE;
    }

    std::string_view cmd(argv[1]);

    if (cmd == "monitor") {
        run_monitor();
        return EXIT_SUCCESS;
    } else if (cmd == "trace") {
        return handle_trace(argc, argv);
    } else if (cmd == "version") {
        printf("BootloaderSpoofer Ptrace Injector v1.0.0\n");
        printf("Based on NeoZygisk by JingMatrix\n");
        return EXIT_SUCCESS;
    } else {
        fprintf(stderr, "error: unknown command '%s'\n", argv[1]);
        print_usage(argv[0]);
        return EXIT_FAILURE;
    }
}
