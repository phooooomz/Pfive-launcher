// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// The Android services lxdb calls. Each call's number comes from this phone's framework, and a call whose parameter
// list is not the one lxdb writes is left switched off: a wrong parcel would reach system_server as garbage.
#pragma once

#include <array>
#include <cstdint>
#include <functional>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "binder.hpp"
#include "dex.hpp"

namespace lxdb {

enum class Call {
    CheckService,
    ListServices,
    CreateVirtualDisplay,
    ResizeVirtualDisplay,
    SetVirtualDisplaySurface,
    ReleaseVirtualDisplay,
    SetVirtualDisplayState,
    StartActivityAsUser,
    GetAllRootTaskInfosOnDisplay,
    SetFocusedTask,
    RemoveTask,
    RegisterTaskStackListener,
    SetDisplayImePolicy,
    SyncInputTransactions,
    InjectInputEvent,
    AcquireWakeLock,
    ReleaseWakeLock,
    GetPackageUid,
    GetProfileIds,
    GetContentProviderExternal,
    RemoveContentProviderExternalAsUser,
    Count,
};

class Android {
public:
    /** Reads the framework and finds the services. False only when nothing can work. */
    bool open(std::string* why);

    bool ready(Call call) const { return bound_[static_cast<size_t>(call)].ok; }
    /** A parcel with the call's interface header. */
    Parcel begin(Call call) const;
    int transact(Call call, const Parcel& data, Reply* reply, uint32_t flags = 0);

    /** A handle kept for the life of the process; 0 when the service does not exist. */
    uint32_t service(const char* name);
    std::vector<std::string> listServices();

    /** Every call's service, number and state, or why it is off. */
    std::string report() const;
    /** The calls LX windows cannot go without are all on. */
    bool windowsReady() const;

    /** IContentProvider.CALL_TRANSACTION. */
    uint32_t providerCall() const { return providerCall_; }
    /** The number of a callback of ITaskStackListener; 0 when this framework has none of that name. */
    uint32_t taskListenerCode(const char* method) const;
    /** AttributionSourceState gained deviceId in Android 14. */
    bool sourceHasDeviceId() const { return sourceHasDeviceId_; }
    /** Called when system_server ends, which takes every display along. */
    void watchSystem(std::function<void()> onDeath);

private:
    struct Bound {
        uint32_t code = 0;
        bool ok = false;
        std::string problem;
    };

    /** Read once, then closed: the daemon keeps the numbers, not the tens of megabytes of dex. */
    Jar framework_;
    std::map<std::string, uint32_t> listenerCodes_;
    std::array<Bound, static_cast<size_t>(Call::Count)> bound_;
    std::map<std::string, uint32_t> services_;
    std::mutex lock_;
    uint32_t providerCall_ = 0;
    bool sourceHasDeviceId_ = false;
};

}  // namespace lxdb
