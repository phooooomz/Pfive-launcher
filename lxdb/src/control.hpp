// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// The socket the `lxdb` command talks to its daemon over, from `adb shell`. It is a Unix socket in the abstract namespace: no
// port, nothing on any network. The kernel names each peer, so the daemon knows who connected and the client knows who
// answered. LX itself uses Binder: SELinux refuses apps a connection to a shell process.
#pragma once

#include <functional>
#include <string>
#include <string_view>
#include <vector>

namespace lxdb {

constexpr int kNoFd = -1;

std::string socketName(int userId);

/** What the daemon answers on the socket. Text is printed by the client; [end] closes the daemon. */
struct ControlHandler {
    std::function<std::string()> status;
    std::function<std::string()> doctor;
    std::function<std::string()> windows;
    std::function<void()> end;
};

/**
 * Binds the socket, taking it over from an older daemon of this user if one holds it, and returns its descriptor. -1: another
 * daemon would not let go. Later connections are served by [serve].
 */
int bindControl(int userId);
void serveControl(int listenFd, ControlHandler handler);

/** Client side. All return the process exit status for `lxdb`. */
int askDaemon(int userId, const std::string& verb);
/** Runs [command] in the daemon's context on the three descriptors of this process. */
int execOnDaemon(int userId, const std::string& command, int in, int out, int err);
/** The same with a terminal; [command] may be empty for an interactive sh. */
int terminalOnDaemon(int userId, const std::string& command);

}  // namespace lxdb
