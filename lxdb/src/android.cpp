// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "android.hpp"

#include <algorithm>

namespace lxdb {

namespace {

constexpr const char* kFramework = "/system/framework/framework.jar";
constexpr const char* kServiceManager = "android.os.IServiceManager";
constexpr const char* kProvider = "android.content.IContentProvider";
constexpr const char* kSource = "android.content.AttributionSourceState";
constexpr const char* kTaskListener = "android.app.ITaskStackListener";
/** The ITaskStackListener callbacks EmbedService listens to; their numbers are read while the framework is open. */
constexpr const char* kListenerMethods[] = {"onActivityRequestedOrientationChanged", "onTaskRequestedOrientationChanged",
                                            "onTaskMovedToFront", "onTaskRemoved"};
constexpr uint32_t kCheckServiceDefault = 2;  // IServiceManager's own place since Android 11
constexpr int32_t kDumpAll = 15;  // IServiceManager.DUMP_FLAG_PRIORITY_ALL

struct Spec {
    const char* service;  // null: the context manager
    const char* iface;
    const char* method;
    const char* shape;
    bool forWindows;
};

// In the order of enum class Call.
constexpr Spec kSpecs[] = {
    {nullptr, kServiceManager, "checkService", "(Ljava/lang/String;)Landroid/os/IBinder;", true},
    {nullptr, kServiceManager, "listServices", "(I)[Ljava/lang/String;", false},
    {"display", "android.hardware.display.IDisplayManager", "createVirtualDisplay",
     "(Landroid/hardware/display/VirtualDisplayConfig;Landroid/hardware/display/IVirtualDisplayCallback;"
     "Landroid/media/projection/IMediaProjection;Ljava/lang/String;)I", true},
    {"display", "android.hardware.display.IDisplayManager", "resizeVirtualDisplay",
     "(Landroid/hardware/display/IVirtualDisplayCallback;III)V", true},
    {"display", "android.hardware.display.IDisplayManager", "setVirtualDisplaySurface",
     "(Landroid/hardware/display/IVirtualDisplayCallback;Landroid/view/Surface;)V", true},
    {"display", "android.hardware.display.IDisplayManager", "releaseVirtualDisplay",
     "(Landroid/hardware/display/IVirtualDisplayCallback;)V", true},
    {"display", "android.hardware.display.IDisplayManager", "setVirtualDisplayState",
     "(Landroid/hardware/display/IVirtualDisplayCallback;Z)V", true},
    {"activity_task", "android.app.IActivityTaskManager", "startActivityAsUser",
     "(Landroid/app/IApplicationThread;Ljava/lang/String;Ljava/lang/String;Landroid/content/Intent;Ljava/lang/String;"
     "Landroid/os/IBinder;Ljava/lang/String;IILandroid/app/ProfilerInfo;Landroid/os/Bundle;I)I", true},
    {"activity_task", "android.app.IActivityTaskManager", "getAllRootTaskInfosOnDisplay", "(I)Ljava/util/List;", true},
    {"activity_task", "android.app.IActivityTaskManager", "setFocusedTask", "(I)V", false},
    {"activity_task", "android.app.IActivityTaskManager", "removeTask", "(I)Z", false},
    {"activity_task", "android.app.IActivityTaskManager", "registerTaskStackListener", "(Landroid/app/ITaskStackListener;)V", false},
    {"window", "android.view.IWindowManager", "setDisplayImePolicy", "(II)V", false},
    {"window", "android.view.IWindowManager", "syncInputTransactions", "(Z)V", false},
    {"input", "android.hardware.input.IInputManager", "injectInputEvent", "(Landroid/view/InputEvent;I)Z", true},
    {"power", "android.os.IPowerManager", "acquireWakeLock",
     "(Landroid/os/IBinder;ILjava/lang/String;Ljava/lang/String;Landroid/os/WorkSource;Ljava/lang/String;I"
     "Landroid/os/IWakeLockCallback;)V", false},
    {"power", "android.os.IPowerManager", "releaseWakeLock", "(Landroid/os/IBinder;I)V", false},
    {"package", "android.content.pm.IPackageManager", "getPackageUid", "(Ljava/lang/String;JI)I", true},
    {"user", "android.os.IUserManager", "getProfileIds", "(IZ)[I", false},
    {"activity", "android.app.IActivityManager", "getContentProviderExternal",
     "(Ljava/lang/String;ILandroid/os/IBinder;Ljava/lang/String;)Landroid/app/ContentProviderHolder;", true},
    {"activity", "android.app.IActivityManager", "removeContentProviderExternalAsUser",
     "(Ljava/lang/String;Landroid/os/IBinder;I)V", false},
};
static_assert(std::size(kSpecs) == static_cast<size_t>(Call::Count));

const Spec& specOf(Call call) { return kSpecs[static_cast<size_t>(call)]; }

}  // namespace

bool Android::open(std::string* why) {
    if (!Binder::get().open(why)) return false;
    std::string jarProblem;
    if (!framework_.open(kFramework, &jarProblem)) {
        // The service manager's own place has not changed since Android 11, so `lxdb services` still answers.
        bound_[static_cast<size_t>(Call::CheckService)] = {kCheckServiceDefault, true, ""};
        for (size_t i = 1; i < bound_.size(); i++) bound_[i].problem = jarProblem;
        return true;
    }
    for (size_t i = 0; i < bound_.size(); i++) {
        const Spec& spec = kSpecs[i];
        Bound& bound = bound_[i];
        const std::string stub = dexDescriptor(spec.iface, "$Stub");
        const auto code = framework_.staticInt(stub, std::string("TRANSACTION_") + spec.method);
        const auto shapes = framework_.methodShapes(dexDescriptor(spec.iface), spec.method);
        if (!code) {
            bound.problem = "not in this framework";
        } else if (std::find(shapes.begin(), shapes.end(), spec.shape) == shapes.end()) {
            bound.problem = "takes " + (shapes.empty() ? std::string("something else") : shapes.front());
        } else {
            bound.code = static_cast<uint32_t>(*code);
            bound.ok = true;
        }
    }
    providerCall_ = static_cast<uint32_t>(framework_.staticInt(dexDescriptor(kProvider), "CALL_TRANSACTION").value_or(0));
    sourceHasDeviceId_ = framework_.hasField(dexDescriptor(kSource), "deviceId");
    for (const char* method : kListenerMethods) {
        const auto code = framework_.staticInt(dexDescriptor(kTaskListener, "$Stub"), std::string("TRANSACTION_") + method);
        if (code) listenerCodes_[method] = static_cast<uint32_t>(*code);
    }
    // Everything the daemon needs from the framework is in bound_, providerCall_ and listenerCodes_ now.
    framework_.close();
    if (!ready(Call::CheckService)) bound_[static_cast<size_t>(Call::CheckService)] = {kCheckServiceDefault, true, ""};
    return true;
}

Parcel Android::begin(Call call) const {
    Parcel p;
    p.writeInterfaceToken(specOf(call).iface);
    return p;
}

int Android::transact(Call call, const Parcel& data, Reply* reply, uint32_t flags) {
    if (!ready(call)) return kUnknownTransaction;
    const Spec& spec = specOf(call);
    const uint32_t handle = spec.service != nullptr ? service(spec.service) : 0;
    if (spec.service != nullptr && handle == 0) return kDeadObject;
    return Binder::get().transact(handle, bound_[static_cast<size_t>(call)].code, data, reply, flags);
}

uint32_t Android::service(const char* name) {
    {
        const std::lock_guard<std::mutex> guard(lock_);
        const auto known = services_.find(name);
        if (known != services_.end()) return known->second;
    }
    Parcel p = begin(Call::CheckService);
    p.writeString16(name);
    Reply reply;
    if (transact(Call::CheckService, p, &reply) != 0) return 0;
    ParcelReader& r = reply.reader();
    if (r.readStatus() != 0) return 0;
    const FlatObject object = r.readObject();
    if (object.type != kTypeHandle) return 0;
    const auto handle = static_cast<uint32_t>(object.handle);
    Binder::get().acquire(handle);
    const std::lock_guard<std::mutex> guard(lock_);
    services_.emplace(name, handle);
    return handle;
}

std::vector<std::string> Android::listServices() {
    std::vector<std::string> names;
    Parcel p = begin(Call::ListServices);
    p.writeInt32(kDumpAll);
    Reply reply;
    if (transact(Call::ListServices, p, &reply) != 0) return names;
    ParcelReader& r = reply.reader();
    if (r.readStatus() != 0) return names;
    const int32_t count = r.readInt32();
    for (int32_t i = 0; i < count && r.ok(); i++) names.push_back(r.readString16());
    return names;
}

void Android::watchSystem(std::function<void()> onDeath) {
    const uint32_t activity = service("activity");
    if (activity != 0) Binder::get().linkToDeath(activity, std::move(onDeath));
}

uint32_t Android::taskListenerCode(const char* method) const {
    const auto found = listenerCodes_.find(method);
    return found == listenerCodes_.end() ? 0 : found->second;
}

bool Android::windowsReady() const {
    for (size_t i = 0; i < bound_.size(); i++) {
        if (kSpecs[i].forWindows && !bound_[i].ok) return false;
    }
    return providerCall_ != 0;
}

std::string Android::report() const {
    std::string out;
    for (size_t i = 0; i < bound_.size(); i++) {
        const Spec& spec = kSpecs[i];
        const Bound& bound = bound_[i];
        out += spec.service != nullptr ? spec.service : "servicemanager";
        out += ' ';
        out += spec.method;
        if (bound.ok) out += " #" + std::to_string(bound.code) + " ok\n";
        else out += std::string(spec.forWindows ? " MISSING: " : " off: ") + bound.problem + "\n";
    }
    out += "provider call #" + std::to_string(providerCall_) + "\n";
    return out;
}

}  // namespace lxdb
