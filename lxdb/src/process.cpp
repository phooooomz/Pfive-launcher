// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "process.hpp"

#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>

namespace lxdb {

namespace {

constexpr const char* kShell = "/system/bin/sh";
constexpr size_t kChunk = 16 * 1024;
// How often a child whose output has ended is checked for its exit.
constexpr useconds_t kReapPollUs = 10 * 1000;
constexpr int kCloseFrom = 3;

int64_t nowMs() {
    timespec t{};
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

/** Between fork and exec: only what is safe in a child of a threaded process. */
[[noreturn]] void becomeShell(const char* command, int in, int out, int err, bool terminal) {
    if (terminal) {
        setsid();
        ioctl(in, TIOCSCTTY, 0);
    }
    dup2(in, 0);
    dup2(out, 1);
    dup2(err, 2);
    const long limit = sysconf(_SC_OPEN_MAX);
    for (int fd = kCloseFrom; fd < limit && fd < 1024; fd++) close(fd);
    signal(SIGPIPE, SIG_DFL);
    if (command != nullptr) execl(kShell, "sh", "-c", command, static_cast<char*>(nullptr));
    else execl(kShell, "sh", static_cast<char*>(nullptr));
    _exit(kExitCannotRun);
}

}  // namespace

int exitStatus(int waitStatus) {
    if (WIFEXITED(waitStatus)) return WEXITSTATUS(waitStatus);
    if (WIFSIGNALED(waitStatus)) return 128 + WTERMSIG(waitStatus);
    return kExitCannotRun;
}

pid_t spawnOn(const std::string& command, int in, int out, int err, bool terminal) {
    const char* text = command.empty() ? nullptr : command.c_str();
    const pid_t pid = fork();
    if (pid == 0) becomeShell(text, in, out, err, terminal);
    return pid;
}

int runCaptured(const std::string& command, std::string_view input, int timeoutMs, size_t maxOutput, std::string* output) {
    int toChild[2];
    int fromChild[2];
    if (pipe2(toChild, O_CLOEXEC) != 0) return kExitCannotRun;
    if (pipe2(fromChild, O_CLOEXEC) != 0) {
        close(toChild[0]);
        close(toChild[1]);
        return kExitCannotRun;
    }
    const pid_t pid = spawnOn(command, toChild[0], fromChild[1], fromChild[1], false);
    close(toChild[0]);
    close(fromChild[1]);
    if (pid < 0) {
        close(toChild[1]);
        close(fromChild[0]);
        return kExitCannotRun;
    }
    fcntl(toChild[1], F_SETFL, O_NONBLOCK);
    size_t sent = 0;
    int writeFd = toChild[1];
    if (input.empty()) {
        close(writeFd);
        writeFd = -1;
    }
    const int64_t deadline = nowMs() + timeoutMs;
    bool timedOut = false;
    char chunk[kChunk];
    for (;;) {
        pollfd fds[2] = {{fromChild[0], POLLIN, 0}, {writeFd, POLLOUT, 0}};
        const int64_t left = deadline - nowMs();
        if (left <= 0) {
            timedOut = true;
            kill(pid, SIGKILL);
            break;
        }
        const int ready = poll(fds, writeFd >= 0 ? 2 : 1, static_cast<int>(std::min<int64_t>(left, 1000)));
        if (ready < 0 && errno != EINTR) break;
        if (ready <= 0) continue;
        if ((fds[0].revents & (POLLIN | POLLHUP | POLLERR)) != 0) {
            const ssize_t n = read(fromChild[0], chunk, sizeof(chunk));
            if (n == 0) break;
            if (n < 0 && errno != EINTR) break;
            if (n > 0 && output->size() < maxOutput) output->append(chunk, std::min(static_cast<size_t>(n), maxOutput - output->size()));
        }
        if (writeFd >= 0 && (fds[1].revents & (POLLOUT | POLLERR | POLLHUP)) != 0) {
            const ssize_t n = write(writeFd, input.data() + sent, input.size() - sent);
            if (n > 0) sent += static_cast<size_t>(n);
            if (sent >= input.size() || (n < 0 && errno != EAGAIN && errno != EINTR)) {
                close(writeFd);
                writeFd = -1;
            }
        }
    }
    if (writeFd >= 0) close(writeFd);
    close(fromChild[0]);
    int status = 0;
    // A child can close its output and keep running ("exec >/dev/null; sleep 600"). It gets until the same deadline
    // and is then killed, instead of holding one of the daemon's few binder threads for as long as it runs.
    for (;;) {
        const pid_t done = waitpid(pid, &status, WNOHANG);
        if (done == pid) break;
        if (done < 0 && errno != EINTR) break;
        if (!timedOut && nowMs() >= deadline) {
            timedOut = true;
            kill(pid, SIGKILL);
        }
        usleep(kReapPollUs);
    }
    return timedOut ? kExitTimedOut : exitStatus(status);
}

}  // namespace lxdb
