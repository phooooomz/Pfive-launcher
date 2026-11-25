// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "control.hpp"

#include <android/log.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <thread>

#include "process.hpp"
#include "rules.hpp"

#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "lxdb", __VA_ARGS__)

namespace lxdb {

namespace {

// First byte of each packet from the daemon.
constexpr char kText = 'T';
constexpr char kExit = 'E';
constexpr char kTerminal = 'P';
constexpr size_t kPacket = 32 * 1024;
constexpr int kBacklog = 8;
constexpr int kTakeoverTries = 40;
constexpr useconds_t kTakeoverWaitUs = 100 * 1000;
constexpr int kChildPollMs = 200;
constexpr char kVerbExec[] = "exec";
constexpr char kVerbTerminal[] = "pty";
constexpr char kVerbTakeover[] = "takeover";

socklen_t fillAddress(int userId, sockaddr_un* address) {
    const std::string name = socketName(userId);
    std::memset(address, 0, sizeof(*address));
    address->sun_family = AF_UNIX;
    // A leading NUL puts the name in the abstract namespace: no file, and it goes with the socket.
    std::memcpy(address->sun_path + 1, name.data(), name.size());
    return static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());
}

bool peerUid(int fd, uid_t* uid) {
    ucred credentials{};
    socklen_t size = sizeof(credentials);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &credentials, &size) != 0) return false;
    *uid = credentials.uid;
    return true;
}

bool sendPacket(int fd, char kind, const void* data, size_t size, const int* fds, size_t fdCount) {
    iovec parts[2] = {{&kind, 1}, {const_cast<void*>(data), size}};
    msghdr message{};
    message.msg_iov = parts;
    message.msg_iovlen = 2;
    char control[CMSG_SPACE(sizeof(int) * 3)] = {};
    if (fdCount > 0) {
        message.msg_control = control;
        message.msg_controllen = CMSG_SPACE(sizeof(int) * fdCount);
        cmsghdr* header = CMSG_FIRSTHDR(&message);
        header->cmsg_level = SOL_SOCKET;
        header->cmsg_type = SCM_RIGHTS;
        header->cmsg_len = CMSG_LEN(sizeof(int) * fdCount);
        std::memcpy(CMSG_DATA(header), fds, sizeof(int) * fdCount);
    }
    return sendmsg(fd, &message, MSG_NOSIGNAL) >= 0;
}

ssize_t receivePacket(int fd, char* buffer, size_t size, int* fds, size_t* fdCount) {
    iovec part = {buffer, size};
    msghdr message{};
    message.msg_iov = &part;
    message.msg_iovlen = 1;
    char control[CMSG_SPACE(sizeof(int) * 3)] = {};
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    ssize_t n;
    do {
        n = recvmsg(fd, &message, MSG_CMSG_CLOEXEC);
    } while (n < 0 && errno == EINTR);
    if (fdCount != nullptr) *fdCount = 0;
    for (cmsghdr* header = CMSG_FIRSTHDR(&message); header != nullptr; header = CMSG_NXTHDR(&message, header)) {
        if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS || fds == nullptr) continue;
        const size_t count = std::min<size_t>((header->cmsg_len - CMSG_LEN(0)) / sizeof(int), 3);
        std::memcpy(fds, CMSG_DATA(header), sizeof(int) * count);
        if (fdCount != nullptr) *fdCount = count;
    }
    return n;
}

void sendExit(int fd, int status) { sendPacket(fd, kExit, &status, sizeof(status), nullptr, 0); }

void sendText(int fd, const std::string& text) {
    for (size_t at = 0; at < text.size(); at += kPacket) {
        const size_t size = std::min(kPacket, text.size() - at);
        if (!sendPacket(fd, kText, text.data() + at, size, nullptr, 0)) return;
    }
}

/** Waits for the child. A client that goes away takes its command with it, as adb's does. */
int waitForChild(int connection, pid_t pid) {
    for (;;) {
        int status = 0;
        const pid_t done = waitpid(pid, &status, WNOHANG);
        if (done == pid) return exitStatus(status);
        if (done < 0 && errno != EINTR) return kExitCannotRun;
        pollfd peer = {connection, POLLIN | POLLHUP, 0};
        if (poll(&peer, 1, kChildPollMs) > 0 && (peer.revents & (POLLHUP | POLLERR | POLLIN)) != 0) {
            char ignored;
            if (recv(connection, &ignored, 1, MSG_DONTWAIT) <= 0) {
                kill(pid, SIGHUP);
                while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
                }
                return exitStatus(status);
            }
        }
    }
}

void runExec(int connection, const std::string& command, const int* fds, size_t count) {
    if (count != 3) {
        // The descriptors that did come are closed too; a peer sending one at a time used up the daemon's limit.
        for (size_t i = 0; i < count; i++) close(fds[i]);
        sendExit(connection, kExitCannotRun);
        return;
    }
    const pid_t pid = spawnOn(command, fds[0], fds[1], fds[2], false);
    for (size_t i = 0; i < count; i++) close(fds[i]);
    sendExit(connection, pid > 0 ? waitForChild(connection, pid) : kExitCannotRun);
}

void runTerminal(int connection, const std::string& command) {
    const int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    char path[64] = {};
    if (master < 0 || grantpt(master) != 0 || unlockpt(master) != 0 || ptsname_r(master, path, sizeof(path)) != 0) {
        if (master >= 0) close(master);
        sendExit(connection, kExitCannotRun);
        return;
    }
    const int line = open(path, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (line < 0) {
        close(master);
        sendExit(connection, kExitCannotRun);
        return;
    }
    const pid_t pid = spawnOn(command, line, line, line, true);
    close(line);
    sendPacket(connection, kTerminal, nullptr, 0, &master, 1);
    close(master);
    sendExit(connection, pid > 0 ? waitForChild(connection, pid) : kExitCannotRun);
}

void serveOne(int connection, const ControlHandler& handler) {
    char request[kPacket];
    int fds[3] = {kNoFd, kNoFd, kNoFd};
    size_t count = 0;
    const ssize_t n = receivePacket(connection, request, sizeof(request) - 1, fds, &count);
    if (n <= 0) return;
    request[n] = '\0';
    const std::string verb = request;
    const size_t argAt = verb.size() + 1;
    const std::string argument = argAt < static_cast<size_t>(n) ? std::string(request + argAt, static_cast<size_t>(n) - argAt) : std::string();
    if (verb == kVerbExec) {
        runExec(connection, argument, fds, count);
        return;
    }
    for (size_t i = 0; i < count; i++) close(fds[i]);
    if (verb == kVerbTerminal) {
        runTerminal(connection, argument);
    } else if (verb == "status") {
        sendText(connection, handler.status());
        sendExit(connection, 0);
    } else if (verb == "doctor") {
        sendText(connection, handler.doctor());
        sendExit(connection, 0);
    } else if (verb == "windows") {
        sendText(connection, handler.windows());
        sendExit(connection, 0);
    } else if (verb == "stop" || verb == kVerbTakeover) {
        sendExit(connection, 0);
        handler.end();
    } else {
        sendExit(connection, kExitCannotRun);
    }
}

int connectDaemon(int userId) {
    const int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    sockaddr_un address{};
    const socklen_t size = fillAddress(userId, &address);
    if (connect(fd, reinterpret_cast<sockaddr*>(&address), size) != 0) {
        const int why = errno;
        close(fd);
        errno = why;
        return -1;
    }
    uid_t server = 0;
    // The name is first come, first served, so what answered must prove to be the shell before it sees a descriptor.
    if (!peerUid(fd, &server) || (server != kShellUid && server != kRootUid)) {
        close(fd);
        errno = EPERM;
        return -1;
    }
    return fd;
}

int printReplies(int fd) {
    char packet[kPacket + 1];
    for (;;) {
        const ssize_t n = receivePacket(fd, packet, sizeof(packet), nullptr, nullptr);
        if (n <= 0) {
            static const char message[] = "lxdb: the daemon does not answer this user\n";
            if (write(STDERR_FILENO, message, sizeof(message) - 1) < 0) return kExitCannotRun;
            return kExitCannotRun;
        }
        if (packet[0] == kText) {
            if (write(STDOUT_FILENO, packet + 1, static_cast<size_t>(n - 1)) < 0) return 1;
        } else if (packet[0] == kExit && n >= 5) {
            int status = 0;
            std::memcpy(&status, packet + 1, sizeof(status));
            return status;
        }
    }
}

std::string joinRequest(const char* verb, const std::string& argument) {
    std::string request = verb;
    request.push_back('\0');
    request += argument;
    return request;
}

int noDaemon() {
    const std::string message = std::string("lxdb: cannot reach the daemon: ") + strerror(errno) + "\n";
    if (write(STDERR_FILENO, message.data(), message.size()) < 0) return kExitCannotRun;
    return kExitCannotRun;
}

/** Copies bytes between this terminal and the daemon's until the program on the far side ends. */
int relayTerminal(int connection, int master) {
    termios saved{};
    const bool isTerminal = tcgetattr(STDIN_FILENO, &saved) == 0;
    if (isTerminal) {
        termios raw = saved;
        cfmakeraw(&raw);
        tcsetattr(STDIN_FILENO, TCSANOW, &raw);
        winsize size{};
        if (ioctl(STDIN_FILENO, TIOCGWINSZ, &size) == 0) ioctl(master, TIOCSWINSZ, &size);
    }
    char buffer[4096];
    int status = kExitCannotRun;
    bool typing = true;
    for (;;) {
        pollfd fds[3] = {{master, POLLIN, 0}, {connection, POLLIN, 0}, {typing ? STDIN_FILENO : -1, POLLIN, 0}};
        if (poll(fds, 3, -1) < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if ((fds[0].revents & (POLLIN | POLLHUP)) != 0) {
            const ssize_t n = read(master, buffer, sizeof(buffer));
            if (n > 0 && write(STDOUT_FILENO, buffer, static_cast<size_t>(n)) < 0) break;
            if (n <= 0 && (fds[1].revents & POLLIN) == 0 && errno != EIO) break;
        }
        if ((fds[2].revents & POLLIN) != 0) {
            const ssize_t n = read(STDIN_FILENO, buffer, sizeof(buffer));
            if (n > 0 && write(master, buffer, static_cast<size_t>(n)) < 0) break;
            if (n <= 0) typing = false;
        }
        if ((fds[1].revents & (POLLIN | POLLHUP)) != 0) {
            status = printReplies(connection);
            break;
        }
    }
    if (isTerminal) tcsetattr(STDIN_FILENO, TCSANOW, &saved);
    return status;
}

}  // namespace

std::string socketName(int userId) { return "lxdb-u" + std::to_string(userId); }

int bindControl(int userId) {
    sockaddr_un address{};
    const socklen_t size = fillAddress(userId, &address);
    const int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    for (int tries = 0; tries < kTakeoverTries; tries++) {
        if (bind(fd, reinterpret_cast<sockaddr*>(&address), size) == 0) {
            if (listen(fd, kBacklog) != 0) break;
            return fd;
        }
        if (errno != EADDRINUSE) break;
        // Only one daemon: a running one belongs to an LX that lost its token, which is why this one was started.
        if (tries == 0) {
            const int older = connectDaemon(userId);
            if (older >= 0) {
                const std::string request = joinRequest(kVerbTakeover, {});
                if (send(older, request.data(), request.size(), MSG_NOSIGNAL) < 0) LOGW("older daemon did not hear");
                close(older);
            }
        }
        usleep(kTakeoverWaitUs);
    }
    close(fd);
    return -1;
}

void serveControl(int listenFd, ControlHandler handler) {
    for (;;) {
        const int connection = accept4(listenFd, nullptr, nullptr, SOCK_CLOEXEC);
        if (connection < 0) {
            if (errno == EINTR || errno == ECONNABORTED) continue;
            return;
        }
        uid_t uid = 0;
        if (!peerUid(connection, &uid) || !controlAllowed(static_cast<int>(uid))) {
            LOGW("uid %u may not use lxdb", uid);
            close(connection);
            continue;
        }
        std::thread([connection, handler] {
            serveOne(connection, handler);
            close(connection);
        }).detach();
    }
}

int askDaemon(int userId, const std::string& verb) {
    const int fd = connectDaemon(userId);
    if (fd < 0) return noDaemon();
    const std::string request = joinRequest(verb.c_str(), {});
    if (send(fd, request.data(), request.size(), MSG_NOSIGNAL) < 0) {
        close(fd);
        return kExitCannotRun;
    }
    const int status = printReplies(fd);
    close(fd);
    return status;
}

int execOnDaemon(int userId, const std::string& command, int in, int out, int err) {
    const int fd = connectDaemon(userId);
    if (fd < 0) return noDaemon();
    const std::string request = joinRequest(kVerbExec, command);
    if (request.size() >= kPacket) {
        close(fd);
        return kExitCannotRun;
    }
    const int fds[3] = {in, out, err};
    // The first byte of every packet is its kind; a request's kind is its verb, so it needs none.
    iovec part = {const_cast<char*>(request.data()), request.size()};
    msghdr message{};
    message.msg_iov = &part;
    message.msg_iovlen = 1;
    char control[CMSG_SPACE(sizeof(fds))] = {};
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    cmsghdr* header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET;
    header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(fds));
    std::memcpy(CMSG_DATA(header), fds, sizeof(fds));
    if (sendmsg(fd, &message, MSG_NOSIGNAL) < 0) {
        close(fd);
        return kExitCannotRun;
    }
    const int status = printReplies(fd);
    close(fd);
    return status;
}

int terminalOnDaemon(int userId, const std::string& command) {
    const int fd = connectDaemon(userId);
    if (fd < 0) return noDaemon();
    const std::string request = joinRequest(kVerbTerminal, command);
    if (send(fd, request.data(), request.size(), MSG_NOSIGNAL) < 0) {
        close(fd);
        return kExitCannotRun;
    }
    char kind = 0;
    int master = kNoFd;
    size_t count = 0;
    const ssize_t n = receivePacket(fd, &kind, 1, &master, &count);
    if (n <= 0 || kind != kTerminal || count != 1) {
        close(fd);
        return kExitCannotRun;
    }
    const int status = relayTerminal(fd, master);
    close(master);
    close(fd);
    return status;
}

}  // namespace lxdb
