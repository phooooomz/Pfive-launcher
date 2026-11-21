// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Running a command through the shell. The children keep none of the daemon's descriptors.
#pragma once

#include <sys/types.h>

#include <cstddef>
#include <string>
#include <string_view>

namespace lxdb {

constexpr int kExitTimedOut = 124;  // as timeout(1) reports it
constexpr int kExitCannotRun = 127;

/**
 * Runs [command] with sh, feeding it [input] and keeping at most [maxOutput] bytes of stdout and stderr together; the rest
 * is read and dropped, so a talkative command finishes anyway. Past [timeoutMs] the command is killed. The exit status.
 */
int runCaptured(const std::string& command, std::string_view input, int timeoutMs, size_t maxOutput, std::string* output);

/** Starts [command] on three descriptors of the caller. [terminal]: the line has its own session and [in] controls it. */
pid_t spawnOn(const std::string& command, int in, int out, int err, bool terminal);

/** The exit status of a child that ended, as sh would report it. */
int exitStatus(int waitStatus);

}  // namespace lxdb
