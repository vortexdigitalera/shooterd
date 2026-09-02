/*
 * Mini Proot - prootd daemon
 *
 * This daemon runs as root (UID 0) and listens on a Unix domain socket
 * for su requests from apps. It verifies the caller's UID against a
 * whitelist and spawns a root shell on demand.
 *
 * The daemon is started by the Zygisk module's post-fs-data hook
 * or by the Magisk/APatch/KernelSU service.sh script.
 *
 * Socket: /dev/prootd (Unix domain socket, mode 0666)
 * Whitelist: /data/adb/bootloaderspoofer/proot_whitelist.txt
 *
 * Protocol:
 *   Client -> "PROOT_HELLO <pid> <uid>\n"
 *   Server -> "PROOT_OK\n" or "PROOT_DENIED\n"
 *   Client -> "PROOT_COMMAND <cmd>\n" or "PROOT_INTERACTIVE\n"
 *   Server -> "PROOT_READY\n"
 *   Bidirectional relay begins
 *   Server -> 1 byte exit code (on command close)
 *
 * Build: see miniproot/build.sh
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <stdarg.h>
#include <signal.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/epoll.h>
#include <sys/prctl.h>
#include <pwd.h>
#include <grp.h>
#include <linux/limits.h>

#define PROOTD_SOCKET "/dev/prootd"
#define PROOTD_PID_FILE "/data/adb/bootloaderspoofer/prootd.pid"
#define PROOTD_LOG_FILE "/data/adb/bootloaderspoofer/prootd.log"
#define WHITELIST_FILE "/data/adb/bootloaderspoofer/proot_whitelist.txt"
#define MAX_CLIENTS 64
#define BUFSZ 65536
#define MAX_EVENTS 32

static const char *TAG = "MiniProot-prootd";

static int log_fd = -1;

static void log_msg(const char *level, const char *fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    int prefix = snprintf(buf, sizeof(buf), "[prootd] [%s] ", level);
    vsnprintf(buf + prefix, sizeof(buf) - prefix - 1, fmt, args);
    va_end(args);

    // Write to log file
    if (log_fd >= 0) {
        size_t len = strlen(buf);
        if (len < sizeof(buf) - 1) {
            buf[len] = '\n';
            buf[len + 1] = '\0';
            write(log_fd, buf, len + 1);
        }
    }

    // Also to stderr (for service.sh debugging)
    fprintf(stderr, "%s\n", buf);
}

#define LOGI(...) log_msg("I", __VA_ARGS__)
#define LOGW(...) log_msg("W", __VA_ARGS__)
#define LOGE(...) log_msg("E", __VA_ARGS__)

/*
 * Client connection state
 */
typedef struct {
    int fd;
    int state;          // 0=hello, 1=command, 2=relay, 3=closing
    pid_t client_pid;
    uid_t client_uid;
    pid_t shell_pid;
    int shell_fd;       // socketpair to shell process
    int is_interactive;
} client_t;

static client_t clients[MAX_CLIENTS];
static int epoll_fd = -1;

/*
 * Read whitelist of allowed UIDs.
 * Format: one UID per line, or "all" to allow everyone.
 * Returns 1 if allowed, 0 if denied.
 */
static int check_whitelist(uid_t uid) {
    FILE *f = fopen(WHITELIST_FILE, "r");
    if (!f) {
        // No whitelist = deny all by default (security)
        LOGW("whitelist file not found, denying uid %d", uid);
        return 0;
    }

    char line[256];
    while (fgets(line, sizeof(line), f)) {
        // Trim whitespace
        char *p = line;
        while (*p == ' ' || *p == '\t') p++;
        char *end = p + strlen(p) - 1;
        while (end > p && (*end == '\n' || *end == '\r' || *end == ' ')) *end-- = '\0';

        if (*p == '#' || *p == '\0') continue;

        if (strcmp(p, "all") == 0) {
            fclose(f);
            return 1;
        }

        // Parse UID
        char *endptr;
        long allowed_uid = strtol(p, &endptr, 10);
        if (endptr != p && (uid_t)allowed_uid == uid) {
            fclose(f);
            return 1;
        }
    }

    fclose(f);
    return 0;
}

/*
 * Spawn a root shell process.
 * Uses fork() + setsid() + execve("/system/bin/sh").
 * Returns the shell's side of a socketpair, or -1 on failure.
 */
static int spawn_root_shell(const char *command, int is_interactive) {
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) < 0) {
        LOGE("socketpair: %s", strerror(errno));
        return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork: %s", strerror(errno));
        close(sv[0]);
        close(sv[1]);
        return -1;
    }

    if (pid == 0) {
        // Child process
        close(sv[0]);  // Close parent's side

        // Set up stdio from socketpair
        dup2(sv[1], STDIN_FILENO);
        dup2(sv[1], STDOUT_FILENO);
        dup2(sv[1], STDERR_FILENO);
        if (sv[1] > STDERR_FILENO) close(sv[1]);

        // Create new session
        setsid();

        // Set environment
        setenv("PATH", "/system/bin:/system/xbin:/vendor/bin:/sbin:/su/bin", 1);
        setenv("TERM", "xterm-256color", 1);
        setenv("HOME", "/root", 1);
        setenv("SHELL", "/system/bin/sh", 1);
        setenv("PROOT_ROOT", "1", 1);

        // Drop to root UID/GID
        setgid(0);
        setuid(0);

        // Set supplementary groups
        gid_t groups[] = {0, 1000, 1003, 1004, 1007, 1011, 1023, 3003, 3009};
        setgroups(sizeof(groups) / sizeof(groups[0]), groups);

        if (command && !is_interactive) {
            // Non-interactive: run command and exit
            char *args[] = {"/system/bin/sh", "-c", (char *)command, NULL};
            execv("/system/bin/sh", args);
        } else {
            // Interactive shell
            char *args[] = {"/system/bin/sh", NULL};
            execv("/system/bin/sh", args);
        }

        // If we get here, exec failed
        LOGE("execv failed: %s", strerror(errno));
        _exit(127);
    }

    // Parent
    close(sv[1]);  // Close child's side
    LOGI("spawned root shell pid=%d (command=%s, interactive=%d)",
         pid, command ? command : "(null)", is_interactive);
    return sv[0];
}

/*
 * Find a free client slot.
 */
static int find_free_slot() {
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i].fd < 0) return i;
    }
    return -1;
}

/*
 * Handle a new client connection.
 */
static void handle_new_connection(int listen_fd) {
    int fd = accept4(listen_fd, NULL, NULL, SOCK_NONBLOCK);
    if (fd < 0) {
        if (errno != EAGAIN && errno != EWOULDBLOCK) {
            LOGE("accept: %s", strerror(errno));
        }
        return;
    }

    int slot = find_free_slot();
    if (slot < 0) {
        LOGW("max clients reached, rejecting");
        close(fd);
        return;
    }

    memset(&clients[slot], 0, sizeof(client_t));
    clients[slot].fd = fd;
    clients[slot].state = 0;  // waiting for hello
    clients[slot].shell_fd = -1;

    struct epoll_event ev;
    ev.events = EPOLLIN;
    ev.data.fd = fd;
    epoll_ctl(epoll_fd, EPOLL_CTL_ADD, fd, &ev);

    LOGI("new client fd=%d slot=%d", fd, slot);
}

/*
 * Find client slot by fd.
 */
static int find_client_by_fd(int fd) {
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i].fd == fd) return i;
    }
    return -1;
}

/*
 * Find client slot by shell_fd.
 */
static int find_client_by_shell_fd(int shell_fd) {
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i].shell_fd == shell_fd) return i;
    }
    return -1;
}

/*
 * Close a client connection and clean up.
 */
static void close_client(int slot) {
    if (slot < 0 || slot >= MAX_CLIENTS) return;
    client_t *c = &clients[slot];

    if (c->fd >= 0) {
        epoll_ctl(epoll_fd, EPOLL_CTL_DEL, c->fd, NULL);
        close(c->fd);
    }
    if (c->shell_fd >= 0) {
        epoll_ctl(epoll_fd, EPOLL_CTL_DEL, c->shell_fd, NULL);
        close(c->shell_fd);
    }

    // Reap shell process
    if (c->shell_pid > 0) {
        int status;
        waitpid(c->shell_pid, &status, WNOHANG);
    }

    memset(c, 0, sizeof(client_t));
    c->fd = -1;
    c->shell_fd = -1;
}

/*
 * Send exit code to client and close.
 */
static void send_exit_and_close(int slot, int exit_code) {
    if (slot < 0 || slot >= MAX_CLIENTS) return;
    client_t *c = &clients[slot];

    if (c->fd >= 0) {
        char ec = (char)(exit_code & 0xff);
        write(c->fd, &ec, 1);
    }
    close_client(slot);
}

/*
 * Handle data from a client.
 */
static void handle_client_data(int slot) {
    if (slot < 0 || slot >= MAX_CLIENTS) return;
    client_t *c = &clients[slot];
    char buf[BUFSZ];

    ssize_t n = read(c->fd, buf, sizeof(buf));
    if (n <= 0) {
        LOGI("client fd=%d disconnected", c->fd);
        close_client(slot);
        return;
    }

    switch (c->state) {
        case 0: {  // Hello
            buf[n] = '\0';
            // Parse "PROOT_HELLO <pid> <uid>\n"
            if (sscanf(buf, "PROOT_HELLO %d %d", &c->client_pid, &c->client_uid) == 2) {
                LOGI("hello from pid=%d uid=%d", c->client_pid, c->client_uid);

                // Check whitelist
                if (check_whitelist(c->client_uid)) {
                    LOGI("uid %d authorized", c->client_uid);
                    write(c->fd, "PROOT_OK\n", 9);
                    c->state = 1;  // waiting for command
                } else {
                    LOGW("uid %d denied (not in whitelist)", c->client_uid);
                    write(c->fd, "PROOT_DENIED\n", 13);
                    close_client(slot);
                }
            } else {
                LOGW("invalid hello: %s", buf);
                close_client(slot);
            }
            break;
        }

        case 1: {  // Command
            buf[n] = '\0';
            char *command = NULL;
            int is_interactive = 0;

            if (strncmp(buf, "PROOT_INTERACTIVE", 16) == 0) {
                is_interactive = 1;
                LOGI("interactive shell requested by uid %d", c->client_uid);
            } else if (strncmp(buf, "PROOT_COMMAND ", 13) == 0) {
                command = strdup(buf + 13);
                // Strip trailing newline
                char *nl = strchr(command, '\n');
                if (nl) *nl = '\0';
                LOGI("command from uid %d: %s", c->client_uid, command);
            } else {
                LOGW("invalid command: %s", buf);
                close_client(slot);
                break;
            }

            // Spawn root shell
            int shell_fd = spawn_root_shell(command, is_interactive);
            if (shell_fd < 0) {
                LOGE("failed to spawn root shell");
                close_client(slot);
                if (command) free(command);
                break;
            }

            c->shell_fd = shell_fd;
            c->is_interactive = is_interactive;

            // Add shell_fd to epoll
            struct epoll_event ev;
            ev.events = EPOLLIN;
            ev.data.fd = shell_fd;
            epoll_ctl(epoll_fd, EPOLL_CTL_ADD, shell_fd, &ev);

            // Send ready signal
            write(c->fd, "PROOT_READY\n", 12);
            c->state = 2;  // relay mode

            if (command) free(command);
            break;
        }

        case 2: {  // Relay: client -> shell
            if (c->shell_fd >= 0) {
                ssize_t w = write(c->shell_fd, buf, n);
                if (w < 0) {
                    LOGW("write to shell failed: %s", strerror(errno));
                    close_client(slot);
                }
            }
            break;
        }

        default:
            break;
    }
}

/*
 * Handle data from a shell process (shell -> client).
 */
static void handle_shell_data(int shell_fd) {
    int slot = find_client_by_shell_fd(shell_fd);
    if (slot < 0) return;
    client_t *c = &clients[slot];

    char buf[BUFSZ];
    ssize_t n = read(shell_fd, buf, sizeof(buf));
    if (n > 0) {
        // Forward to client
        ssize_t w = write(c->fd, buf, n);
        if (w < 0) {
            LOGW("write to client failed: %s", strerror(errno));
            close_client(slot);
        }
    } else if (n == 0) {
        // Shell exited
        LOGI("shell pid=%d exited", c->shell_pid);

        // Get exit status
        int status = 0;
        if (c->shell_pid > 0) {
            waitpid(c->shell_pid, &status, 0);
            if (WIFEXITED(status)) {
                status = WEXITSTATUS(status);
            } else if (WIFSIGNALED(status)) {
                status = 128 + WTERMSIG(status);
            }
        }

        send_exit_and_close(slot, status);
    }
}

/*
 * Create the listening socket.
 */
static int create_listen_socket() {
    // Remove existing socket
    unlink(PROOTD_SOCKET);

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK, 0);
    if (fd < 0) {
        LOGE("socket: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, PROOTD_SOCKET, sizeof(addr.sun_path) - 1);

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        LOGE("bind: %s", strerror(errno));
        close(fd);
        return -1;
    }

    // Make socket world-accessible (apps need to connect)
    chmod(PROOTD_SOCKET, 0666);

    if (listen(fd, MAX_CLIENTS) < 0) {
        LOGE("listen: %s", strerror(errno));
        close(fd);
        return -1;
    }

    LOGI("listening on %s", PROOTD_SOCKET);
    return fd;
}

/*
 * Check if another instance is already running.
 */
static int check_single_instance() {
    FILE *f = fopen(PROOTD_PID_FILE, "r");
    if (f) {
        char buf[32];
        if (fgets(buf, sizeof(buf), f)) {
            pid_t old_pid = atoi(buf);
            fclose(f);
            if (old_pid > 0 && kill(old_pid, 0) == 0) {
                LOGW("prootd already running (pid=%d), exiting", old_pid);
                return 1;
            }
        } else {
            fclose(f);
        }
    }

    // Write our PID
    f = fopen(PROOTD_PID_FILE, "w");
    if (f) {
        fprintf(f, "%d\n", getpid());
        fclose(f);
    }
    return 0;
}

/*
 * Signal handler for graceful shutdown.
 */
static void sig_handler(int sig) {
    LOGI("received signal %d, shutting down", sig);
    unlink(PROOTD_SOCKET);
    unlink(PROOTD_PID_FILE);
    _exit(0);
}

/*
 * Reap zombie processes.
 */
static void sigchld_handler(int sig) {
    while (waitpid(-1, NULL, WNOHANG) > 0);
}

int main(int argc, char *argv[]) {
    // Daemonize
    if (getppid() != 1) {
        pid_t pid = fork();
        if (pid < 0) {
            fprintf(stderr, "fork: %s\n", strerror(errno));
            return 1;
        }
        if (pid > 0) {
            // Parent exits
            return 0;
        }
        // Child continues
        setsid();
        umask(0);
    }

    // Set up signal handlers
    signal(SIGTERM, sig_handler);
    signal(SIGINT, sig_handler);
    signal(SIGHUP, sig_handler);
    signal(SIGCHLD, sigchld_handler);
    signal(SIGPIPE, SIG_IGN);

    // Set process name
    prctl(PR_SET_NAME, "prootd", 0, 0, 0);

    // Check single instance
    if (check_single_instance()) {
        return 0;
    }

    // Ensure data directory exists
    mkdir("/data/adb/bootloaderspoofer", 0755);

    // Open log file
    log_fd = open(PROOTD_LOG_FILE, O_WRONLY | O_APPEND | O_CREAT, 0644);

    LOGI("prootd starting (pid=%d, uid=%d)", getpid(), getuid());

    // Create listening socket
    int listen_fd = create_listen_socket();
    if (listen_fd < 0) {
        LOGE("failed to create listen socket");
        return 1;
    }

    // Create default whitelist if not exists
    if (access(WHITELIST_FILE, F_OK) != 0) {
        FILE *f = fopen(WHITELIST_FILE, "w");
        if (f) {
            fprintf(f, "# Mini Proot whitelist\n");
            fprintf(f, "# Add UIDs allowed to use su, one per line\n");
            fprintf(f, "# Use 'all' to allow all apps (not recommended)\n");
            fprintf(f, "# Examples:\n");
            fprintf(f, "# 10001  # app with uid 10001\n");
            fprintf(f, "# all   # allow all apps\n");
            fclose(f);
            chmod(WHITELIST_FILE, 0644);
            LOGI("created default whitelist at %s", WHITELIST_FILE);
        }
    }

    // Initialize client slots
    for (int i = 0; i < MAX_CLIENTS; i++) {
        clients[i].fd = -1;
        clients[i].shell_fd = -1;
    }

    // Create epoll
    epoll_fd = epoll_create1(0);
    if (epoll_fd < 0) {
        LOGE("epoll_create1: %s", strerror(errno));
        return 1;
    }

    // Add listen socket to epoll
    struct epoll_event ev;
    ev.events = EPOLLIN;
    ev.data.fd = listen_fd;
    epoll_ctl(epoll_fd, EPOLL_CTL_ADD, listen_fd, &ev);

    // Main event loop
    struct epoll_event events[MAX_EVENTS];
    LOGI("prootd ready, entering event loop");

    for (;;) {
        int n = epoll_wait(epoll_fd, events, MAX_EVENTS, -1);
        if (n < 0) {
            if (errno == EINTR) continue;
            LOGE("epoll_wait: %s", strerror(errno));
            break;
        }

        for (int i = 0; i < n; i++) {
            int fd = events[i].data.fd;

            if (fd == listen_fd) {
                // New connection
                handle_new_connection(listen_fd);
            } else {
                // Check if this is a shell fd or client fd
                int shell_slot = find_client_by_shell_fd(fd);
                if (shell_slot >= 0) {
                    // Shell data
                    if (events[i].events & (EPOLLERR | EPOLLHUP)) {
                        handle_shell_data(fd);  // will detect EOF
                    } else if (events[i].events & EPOLLIN) {
                        handle_shell_data(fd);
                    }
                } else {
                    // Client data
                    int slot = find_client_by_fd(fd);
                    if (slot >= 0) {
                        if (events[i].events & (EPOLLERR | EPOLLHUP | EPOLLRDHUP)) {
                            LOGI("client fd=%d hung up", fd);
                            close_client(slot);
                        } else if (events[i].events & EPOLLIN) {
                            handle_client_data(slot);
                        }
                    }
                }
            }
        }
    }

    // Cleanup
    unlink(PROOTD_SOCKET);
    unlink(PROOTD_PID_FILE);
    close(listen_fd);
    close(epoll_fd);

    return 0;
}
