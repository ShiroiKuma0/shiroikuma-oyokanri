// SPDX-License-Identifier: GPL-3.0-or-later

// Fork (白い熊, +102): the kernel-call probe. When "new processes cannot start", the zygote's
// child dies on seccomp(SECCOMP_SET_MODE_FILTER) = ENOMEM. This makes the same call — and two
// neighbours that draw on the kernel's per-CPU pool — from a throwaway child of this app, so the
// record shows whether the kernel refuses every caller or only the zygote's children.
//
// Each test runs in its own forked child that does nothing but raw system calls and _exit, so a
// filter never lands on the app itself and a seccomp kill (SIGSYS) costs only that child.

#include <jni.h>
#include <errno.h>
#include <sched.h>
#include <signal.h>
#include <stddef.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <linux/aio_abi.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

#define BIG_FILTER 224

static int child_seccomp(int len) {
    static struct sock_filter insns[BIG_FILTER];
    if (len > BIG_FILTER) len = BIG_FILTER;
    for (int i = 0; i < len - 1; i++) {
        insns[i] = (struct sock_filter) BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 0);
    }
    insns[len - 1] = (struct sock_filter) BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    struct sock_fprog prog = {.len = (unsigned short) len, .filter = insns};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return errno;
    if (syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, 0, &prog) != 0) return errno;
    return 0;
}

static int child_io_setup(void) {
    aio_context_t ctx = 0;
    if (syscall(__NR_io_setup, 1, &ctx) != 0) return errno;
    syscall(__NR_io_destroy, ctx);
    return 0;
}

static int child_unshare(void) {
    if (syscall(__NR_unshare, CLONE_NEWUSER | CLONE_NEWNS) != 0) return errno;
    return 0;
}

// 0 = worked, 1..255 = errno, 1000 + n = killed by signal n, -errno = the fork itself failed.
static int run_test(int which) {
    pid_t pid = fork();
    if (pid < 0) return -errno;
    if (pid == 0) {
        int r;
        switch (which) {
            case 0: r = child_seccomp(1); break;
            case 1: r = child_seccomp(BIG_FILTER); break;
            case 2: r = child_io_setup(); break;
            default: r = child_unshare(); break;
        }
        _exit(r & 0xff);
    }
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -errno;
    }
    if (WIFSIGNALED(status)) return 1000 + WTERMSIG(status);
    return WEXITSTATUS(status);
}

JNIEXPORT jintArray JNICALL
Java_io_github_muntashirakon_AppManager_processreaper_KernelProbe_nativeRun(JNIEnv *env, jclass clazz) {
    (void) clazz;
    jint out[5];
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    for (int i = 0; i < 4; i++) out[i] = run_test(i);
    clock_gettime(CLOCK_MONOTONIC, &t1);
    out[4] = (jint) ((t1.tv_sec - t0.tv_sec) * 1000 + (t1.tv_nsec - t0.tv_nsec) / 1000000);
    jintArray arr = (*env)->NewIntArray(env, 5);
    if (arr != NULL) (*env)->SetIntArrayRegion(env, arr, 0, 5, out);
    return arr;
}

// Fork (白い熊, +103, reworked in +104): the socket-filter gauge. A classic filter attached to
// an ordinary UDP socket goes through the same kernel code as a seccomp filter (program
// allocation and conversion) but needs no new process and leaves nothing behind: closing the
// socket frees the program.
//
// +103 attached 40 in a row and found that, on a phone that was starting apps normally, only the
// first 4 got through — the kernel has room for a handful of filter programs at any moment. So
// the test itself must stay small: it now HOLDS at most GAUGE_CAP sockets, counts how many
// attaches work before the first refusal — the free room, in programs — and closes them at once.
//
// The reserve: one socket keeps a filter while there is room. When the gauge reads 0, the
// reserve is released and one attach is tried again for up to a second. If that gets through
// while everything else was refused, the kernel had no room for one more program until we gave
// one back.

#define GAUGE_CAP 6
#define RESERVE_MIN_ROOM 3

static struct sock_filter g_sock_insn;
static int g_reserve_fd = -1;

// 0 = attached (the socket is returned in *fd_out), else errno.
static int sock_attach(int *fd_out) {
    g_sock_insn = (struct sock_filter) BPF_STMT(BPF_RET | BPF_K, 0xffff);
    struct sock_fprog prog = {.len = 1, .filter = &g_sock_insn};
    // The raw call: bionic's socket() would ask netd to tag every one of these.
    int fd = (int) syscall(__NR_socket, AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return errno ? errno : EIO;
    if (setsockopt(fd, SOL_SOCKET, SO_ATTACH_FILTER, &prog, sizeof(prog)) != 0) {
        int e = errno ? errno : EIO;
        close(fd);
        return e;
    }
    *fd_out = fd;
    return 0;
}

// out: {room, cap, firstErrno, reserveState, reserveMs}
// room: attaches that worked before the first refusal (cap = "at least cap").
// reserveState: 0 held and not needed, 1 none (no room to set one up), 2 released -> the next
// attach worked, 3 released -> still refused after a second, 4 newly set up.
JNIEXPORT jintArray JNICALL
Java_io_github_muntashirakon_AppManager_processreaper_KernelProbe_nativeSockets(JNIEnv *env, jclass clazz) {
    (void) clazz;
    jint out[5] = {0, GAUGE_CAP, 0, 0, 0};
    int held[GAUGE_CAP];
    int n = 0;
    while (n < GAUGE_CAP) {
        int fd = -1;
        int e = sock_attach(&fd);
        if (e != 0) {
            out[2] = e;
            break;
        }
        held[n++] = fd;
    }
    for (int i = 0; i < n; i++) close(held[i]);
    out[0] = n;
    if (g_reserve_fd >= 0) {
        if (n == 0 && out[2] == ENOMEM) {
            struct timespec t0, t1;
            clock_gettime(CLOCK_MONOTONIC, &t0);
            close(g_reserve_fd);
            g_reserve_fd = -1;
            out[3] = 3;
            // The kernel frees a released program a little later, from a work queue.
            for (int i = 0; i < 100; i++) {
                int fd = -1;
                if (sock_attach(&fd) == 0) {
                    g_reserve_fd = fd;  // the one we got back is the new reserve
                    out[3] = 2;
                    break;
                }
                usleep(10 * 1000);
            }
            clock_gettime(CLOCK_MONOTONIC, &t1);
            out[4] = (jint) ((t1.tv_sec - t0.tv_sec) * 1000 + (t1.tv_nsec - t0.tv_nsec) / 1000000);
        }
    } else if (n >= RESERVE_MIN_ROOM) {
        usleep(50 * 1000);  // let the gauge's own programs be freed first
        int fd = -1;
        if (sock_attach(&fd) == 0) {
            g_reserve_fd = fd;
            out[3] = 4;
        } else {
            out[3] = 1;
        }
    } else {
        out[3] = 1;
    }
    jintArray arr = (*env)->NewIntArray(env, 5);
    if (arr != NULL) (*env)->SetIntArrayRegion(env, arr, 0, 5, out);
    return arr;
}

// Fork (白い熊, +105): the room, measured. Holds up to `cap` sockets with a filter each, counts
// how many attaches worked before the first refusal, and closes them all. When the count stays
// under the cap the kernel's pool WAS EMPTY for the moment between the last attach and the
// frees — app starts in that moment fail — so the caller decides how high a cap is worth it.
// out: {count, hitWall, firstErrno, tookMs}
JNIEXPORT jintArray JNICALL
Java_io_github_muntashirakon_AppManager_processreaper_KernelProbe_nativeRoom(JNIEnv *env, jclass clazz, jint cap) {
    (void) clazz;
    jint out[4] = {0, 0, 0, 0};
    if (cap < 1) cap = 1;
    if (cap > 20000) cap = 20000;
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    int *held = (int *) malloc(sizeof(int) * (size_t) cap);
    if (held != NULL) {
        int n = 0;
        while (n < cap) {
            int fd = -1;
            int e = sock_attach(&fd);
            if (e != 0) {
                out[1] = e == ENOMEM;
                out[2] = e;
                break;
            }
            held[n++] = fd;
        }
        for (int i = n - 1; i >= 0; i--) close(held[i]);
        free(held);
        out[0] = n;
    } else {
        out[2] = ENOMEM;
    }
    clock_gettime(CLOCK_MONOTONIC, &t1);
    out[3] = (jint) ((t1.tv_sec - t0.tv_sec) * 1000 + (t1.tv_nsec - t0.tv_nsec) / 1000000);
    jintArray arr = (*env)->NewIntArray(env, 4);
    if (arr != NULL) (*env)->SetIntArrayRegion(env, arr, 0, 4, out);
    return arr;
}

// The churn test: `cycles` filter programs created and released one after another, paced so that
// only two or three are ever waiting to be freed. If the room is smaller afterwards, creating and
// releasing a program loses room by itself — which would name process churn as what fills the pool.
// out: {cyclesDone, refusals, tookMs}
JNIEXPORT jintArray JNICALL
Java_io_github_muntashirakon_AppManager_processreaper_KernelProbe_nativeChurn(JNIEnv *env, jclass clazz, jint cycles) {
    (void) clazz;
    jint out[3] = {0, 0, 0};
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    for (int i = 0; i < cycles; i++) {
        int fd = -1;
        int tries = 0;
        while (sock_attach(&fd) != 0) {
            out[1]++;
            if (++tries > 40) goto done;  // two seconds without room: stop
            usleep(50 * 1000);
        }
        close(fd);
        out[0]++;
        usleep(12 * 1000);
    }
done:
    clock_gettime(CLOCK_MONOTONIC, &t1);
    out[2] = (jint) ((t1.tv_sec - t0.tv_sec) * 1000 + (t1.tv_nsec - t0.tv_nsec) / 1000000);
    jintArray arr = (*env)->NewIntArray(env, 3);
    if (arr != NULL) (*env)->SetIntArrayRegion(env, arr, 0, 3, out);
    return arr;
}
