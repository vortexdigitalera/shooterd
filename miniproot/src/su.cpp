/*
 * Mini Proot - su binary replacement
 *
 * This binary acts as a drop-in replacement for the `su` binary.
 * When an app calls `su`, it connects to the prootd daemon via a
 * Unix domain socket and requests a root shell.
 *
 * The prootd daemon (running as root via Zygisk injection) spawns
 * a shell process with UID 0 and connects its stdio to this process
 * via socketpair, providing transparent root access.
 *
 * Usage:
 *   su               - interactive root shell
 *   su -c "command"  - run command as root
 *   su -             - read commands from stdin
 *
 * Build: see miniproot/build.sh
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <stdarg.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <signal.h>
#include <fcntl.h>
#include <poll.h>
#include <termios.h>
#include <sys/ioctl.h>

#define PROOTD_SOCKET "/dev/prootd"
#define PROOTD_VERSION "1.0.0"
#define BUFSZ 65536

static const char *PROOT_TAG = "MiniProot-su";

static void log_err(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    fprintf(stderr, "%s: ", PROOT_TAG);
    vfprintf(stderr, fmt, args);
    fprintf(stderr, "\n");
    va_end(args);
}

static int connect_daemon() {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        log_err("socket: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, PROOTD_SOCKET, sizeof(addr.sun_path) - 1);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        log_err("cannot connect to prootd at %s: %s", PROOTD_SOCKET, strerror(errno));
        log_err("is the Mini Proot module installed and prootd running?");
        close(fd);
        return -1;
    }

    return fd;
}

/*
 * Protocol:
 * 1. Client sends: "PROOT_HELLO <pid> <uid>\n"
 * 2. Server responds: "PROOT_OK\n" or "PROOT_DENIED\n"
 * 3. Client sends: command string (for -c mode) or "PROOT_INTERACTIVE\n"
 * 4. Server spawns root shell, connects stdio via socketpair
 * 5. Bidirectional data relay begins
 */

static int handshake(int fd, const char *command) {
    char buf[1024];
    pid_t pid = getpid();
    uid_t uid = getuid();

    // Send hello with caller identity
    int len = snprintf(buf, sizeof(buf), "PROOT_HELLO %d %d\n", pid, uid);
    if (write(fd, buf, len) != len) {
        log_err("failed to send hello");
        return -1;
    }

    // Read response
    len = read(fd, buf, sizeof(buf) - 1);
    if (len <= 0) {
        log_err("no response from prootd");
        return -1;
    }
    buf[len] = '\0';

    if (strncmp(buf, "PROOT_DENIED", 12) == 0) {
        log_err("root access denied by prootd (app not in whitelist)");
        return -1;
    }

    if (strncmp(buf, "PROOT_OK", 8) != 0) {
        log_err("unexpected response: %s", buf);
        return -1;
    }

    // Send command mode
    if (command) {
        // Non-interactive: send the command
        len = snprintf(buf, sizeof(buf), "PROOT_COMMAND %s\n", command);
        if (write(fd, buf, len) != len) {
            log_err("failed to send command");
            return -1;
        }
    } else {
        // Interactive shell
        if (write(fd, "PROOT_INTERACTIVE\n", 18) != 18) {
            log_err("failed to send interactive mode");
            return -1;
        }
    }

    // Wait for PROOT_READY
    len = read(fd, buf, sizeof(buf) - 1);
    if (len <= 0) {
        log_err("no ready signal from prootd");
        return -1;
    }
    buf[len] = '\0';
    if (strncmp(buf, "PROOT_READY", 11) != 0) {
        log_err("unexpected response: %s", buf);
        return -1;
    }

    return 0;
}

/*
 * Relay data between stdin/stdout and the socket.
 * This creates a transparent pipe between the local terminal
 * and the remote root shell.
 */
static int relay(int sock_fd) {
    struct pollfd fds[2];
    char buf[BUFSZ];

    // Make stdin non-blocking
    int stdin_fd = STDIN_FILENO;
    int stdout_fd = STDOUT_FILENO;

    int flags = fcntl(stdin_fd, F_GETFL, 0);
    fcntl(stdin_fd, F_SETFL, flags | O_NONBLOCK);

    flags = fcntl(sock_fd, F_GETFL, 0);
    fcntl(sock_fd, F_SETFL, flags | O_NONBLOCK);

    for (;;) {
        fds[0].fd = stdin_fd;
        fds[0].events = POLLIN;
        fds[1].fd = sock_fd;
        fds[1].events = POLLIN;

        int ret = poll(fds, 2, -1);
        if (ret < 0) {
            if (errno == EINTR) continue;
            return -1;
        }

        // stdin -> socket
        if (fds[0].revents & POLLIN) {
            ssize_t n = read(stdin_fd, buf, sizeof(buf));
            if (n > 0) {
                ssize_t w = 0;
                while (w < n) {
                    ssize_t r = write(sock_fd, buf + w, n - w);
                    if (r < 0) {
                        if (errno == EAGAIN || errno == EWOULDBLOCK) continue;
                        return -1;
                    }
                    w += r;
                }
            } else if (n == 0) {
                // stdin EOF
                shutdown(sock_fd, SHUT_WR);
            }
        }

        // socket -> stdout
        if (fds[1].revents & POLLIN) {
            ssize_t n = read(sock_fd, buf, sizeof(buf));
            if (n > 0) {
                ssize_t w = 0;
                while (w < n) {
                    ssize_t r = write(stdout_fd, buf + w, n - w);
                    if (r < 0) {
                        if (errno == EAGAIN || errno == EWOULDBLOCK) continue;
                        return -1;
                    }
                    w += r;
                }
            } else if (n == 0) {
                // socket closed = remote shell exited
                return 0;
            }
        }

        // Check for errors
        if (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) {
            // stdin error, continue reading from socket
        }
        if (fds[1].revents & (POLLERR | POLLHUP)) {
            // socket error/hangup
            return 0;
        }
    }
}

static void usage(const char *prog) {
    fprintf(stderr, "Mini Proot su v%s\n", PROOTD_VERSION);
    fprintf(stderr, "Usage: %s [options] [command...]\n", prog);
    fprintf(stderr, "\nOptions:\n");
    fprintf(stderr, "  -c <command>   Run command as root\n");
    fprintf(stderr, "  -              Read commands from stdin\n");
    fprintf(stderr, "  -v, --version  Show version\n");
    fprintf(stderr, "  -h, --help     Show this help\n");
    fprintf(stderr, "\nThis is a Mini Proot su replacement.\n");
    fprintf(stderr, "Root access is provided by the prootd daemon.\n");
}

int main(int argc, char *argv[]) {
    const char *command = NULL;
    int read_stdin = 0;

    // Parse arguments (simplified su-compatible parsing)
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-c") == 0 || strcmp(argv[i], "--command") == 0) {
            if (i + 1 < argc) {
                command = argv[++i];
            } else {
                log_err("option '%s' requires an argument", argv[i]);
                return 1;
            }
        } else if (strcmp(argv[i], "-") == 0) {
            read_stdin = 1;
        } else if (strcmp(argv[i], "-v") == 0 || strcmp(argv[i], "--version") == 0) {
            printf("Mini Proot su v%s\n", PROOTD_VERSION);
            return 0;
        } else if (strcmp(argv[i], "-h") == 0 || strcmp(argv[i], "--help") == 0) {
            usage(argv[0]);
            return 0;
        } else if (argv[i][0] == '-') {
            // Skip other su options (-s, -m, -p, etc.) that we don't need
            if (i + 1 < argc && argv[i + 1][0] != '-') {
                i++; // skip the argument
            }
        } else {
            // First non-option argument is the command
            // Join remaining args as the command
            size_t total = 0;
            for (int j = i; j < argc; j++) {
                total += strlen(argv[j]) + 1;
            }
            char *cmd = malloc(total);
            if (!cmd) {
                log_err("out of memory");
                return 1;
            }
            cmd[0] = '\0';
            for (int j = i; j < argc; j++) {
                if (j > i) strcat(cmd, " ");
                strcat(cmd, argv[j]);
            }
            command = cmd;
            break;
        }
    }

    // If reading from stdin, build command from stdin
    if (read_stdin && !command) {
        char stdin_buf[BUFSZ];
        size_t total = 0;
        ssize_t n;
        char *cmd = malloc(BUFSZ);
        if (!cmd) {
            log_err("out of memory");
            return 1;
        }
        while ((n = read(STDIN_FILENO, stdin_buf, sizeof(stdin_buf))) > 0) {
            if (total + n >= BUFSZ) {
                log_err("stdin command too long");
                free(cmd);
                return 1;
            }
            memcpy(cmd + total, stdin_buf, n);
            total += n;
        }
        cmd[total] = '\0';
        command = cmd;
    }

    // Connect to prootd daemon
    int sock_fd = connect_daemon();
    if (sock_fd < 0) {
        return 1;
    }

    // Perform handshake
    if (handshake(sock_fd, command) < 0) {
        close(sock_fd);
        return 1;
    }

    // Relay data between terminal and remote root shell
    int ret = relay(sock_fd);

    // Get exit code from daemon
    // The daemon sends a single byte exit code before closing
    char exit_byte = 0;
    // Try to read exit code (may not be available if socket already closed)
    int flags_save = fcntl(sock_fd, F_GETFL, 0);
    fcntl(sock_fd, F_SETFL, flags_save & ~O_NONBLOCK);
    ssize_t en = read(sock_fd, &exit_byte, 1);
    close(sock_fd);

    if (en == 1) {
        return (int)(unsigned char)exit_byte;
    }

    return ret;
}
