// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "embed_service.hpp"

#include <android/log.h>
#include <time.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <vector>

#include "process.hpp"
#include "rules.hpp"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "lxdb", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "lxdb", __VA_ARGS__)

namespace lxdb {

namespace {

constexpr const char* kDescriptor = "com.lxlauncher.embed.IEmbedService";
constexpr const char* kClient = "com.lxlauncher.embed.IEmbedClient";
constexpr const char* kShellPackage = "com.android.shell";

// The numbers IEmbedService.aidl gives each method; AIDL adds them to FIRST_CALL_TRANSACTION.
constexpr uint32_t kCreate = kFirstCallTransaction + 1;
constexpr uint32_t kResize = kFirstCallTransaction + 2;
constexpr uint32_t kSetSurface = kFirstCallTransaction + 3;
constexpr uint32_t kStart = kFirstCallTransaction + 4;
constexpr uint32_t kInjectMotion = kFirstCallTransaction + 5;
constexpr uint32_t kInjectKey = kFirstCallTransaction + 6;
constexpr uint32_t kRelease = kFirstCallTransaction + 7;
constexpr uint32_t kExec = kFirstCallTransaction + 8;
constexpr uint32_t kClose = kFirstCallTransaction + 9;
// IEmbedClient.aidl.
constexpr uint32_t kOnDisplayEmpty = kFirstCallTransaction + 1;
constexpr uint32_t kOnOrientation = kFirstCallTransaction + 2;

constexpr int64_t kArrivalTimeoutMs = 8000;
/** No dump read is planned: the task events say when the orientation may have changed. */
constexpr int64_t kNever = INT64_MAX;
constexpr int kEmptyPolls = 2;
/** An app rarely has more than one task on its display; a dialog-like activity can add one. */
constexpr int kMaxTasksToRemove = 4;
constexpr int kInjectAsync = 0;  // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
constexpr int kImePolicyLocal = 0;  // WindowManager.DISPLAY_IME_POLICY_LOCAL
constexpr int kScreenBrightWakeLock = 0x0000000a;  // PowerManager.SCREEN_BRIGHT_WAKE_LOCK
constexpr int kNoTask = -1;
constexpr int kNoAnswer = -2;
/** A task of an id this Android's parcel does not give away; real ids start at 1. */
constexpr int kSomeTask = 0;
constexpr int kExceptionSecurity = -1;  // Parcel.EX_SECURITY
constexpr int kExecTimeoutMaxMs = 120000;
/** Replies go through LX's binder buffer, which is a megabyte. */
constexpr size_t kExecMaxOutput = 512 * 1024;
constexpr size_t kDumpMax = 4 * 1024 * 1024;
constexpr int kDumpTimeoutMs = 8000;

int64_t nowMs() {
    timespec t{};
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

/** Android tells a display's owner when it pauses or stops; the daemon has no use for it. */
class DisplayCallback : public Node {
public:
    std::string_view descriptor() const override { return "android.hardware.display.IVirtualDisplayCallback"; }
    int onTransact(uint32_t, ParcelReader&, Parcel&, const Caller&) override { return 0; }
};

}  // namespace

struct EmbedService::Entry {
    int id = -1;
    uid_t ownerUid = 0;
    uint32_t client = 0;
    Death* death = nullptr;
    DisplayCallback* callback = nullptr;
    Token* awake = nullptr;
    std::string package;
    int width = 0;
    int height = 0;
    bool released = false;
    // The rest belongs to the watcher and is guarded by lock_.
    bool started = false;
    bool sawTask = false;
    bool reported = false;
    bool looking = false;
    int task = kNoTask;
    int emptyPolls = 0;
    int orientation = kOrientationUnknown;
    int64_t startedAt = 0;
    int64_t nextPoll = 0;
    int64_t nextLook = 0;
    int64_t lookEvery = kFirstLookMs;
    /** False while LX's window is hidden (no surface): nobody sees the app, so its orientation is not read. */
    bool shown = true;
};

/**
 * Android's task events. The two orientation events carry the task and the orientation it asks for, so a known task needs
 * no dump; a task this Android's parcel does not name gets a dump read instead. A task that goes away makes the watcher
 * look at once, so a closed app's window goes without waiting for the slow poll.
 */
class EmbedService::TaskListener : public Node {
public:
    TaskListener(EmbedService& service, uint32_t onOrientation, uint32_t onTaskOrientation, uint32_t onFront, uint32_t onRemoved)
        : service_(service), onOrientation_(onOrientation), onTaskOrientation_(onTaskOrientation), onFront_(onFront),
          onRemoved_(onRemoved) {}

    std::string_view descriptor() const override { return "android.app.ITaskStackListener"; }

    /** Both orientation events can arrive, so the dump is read only when neither is known. */
    bool hearsOrientation() const { return onOrientation_ != 0 || onTaskOrientation_ != 0; }

    int onTransact(uint32_t code, ParcelReader& in, Parcel&, const Caller&) override {
        if (code == 0) return 0;
        if (code == onFront_) {
            service_.ring(kNoTask);
            return 0;
        }
        if (code != onOrientation_ && code != onTaskOrientation_ && code != onRemoved_) return 0;
        if (!in.readInterfaceToken(descriptor())) return 0;
        const int taskId = in.readInt32();
        if (code == onRemoved_) {
            service_.taskGone(taskId);
            return 0;
        }
        const int orientation = in.readInt32();
        if (in.ok()) service_.orientationAsked(taskId, orientationOf(orientation));
        else service_.ring(taskId);
        return 0;
    }

private:
    EmbedService& service_;
    const uint32_t onOrientation_;
    const uint32_t onTaskOrientation_;
    const uint32_t onFront_;
    const uint32_t onRemoved_;
};

EmbedService::EmbedService(Android& android, int lxAppId) : android_(android), lxAppId_(lxAppId) {}

std::string_view EmbedService::descriptor() const { return kDescriptor; }

int EmbedService::onTransact(uint32_t code, ParcelReader& in, Parcel& reply, const Caller& caller) {
    if (!callerAllowed(static_cast<int>(caller.uid), lxAppId_)) {
        LOGW("uid %u may not use the embed service", caller.uid);
        return kUnknownTransaction;
    }
    if (!in.readInterfaceToken(kDescriptor)) return kFailedTransaction;
    switch (code) {
        case kCreate:
            reply.writeInt32(0);
            reply.writeInt32(create(in, caller));
            return 0;
        case kResize:
            resize(in, caller);
            return 0;
        case kSetSurface:
            setSurface(in, caller);
            return 0;
        case kStart:
            reply.writeInt32(0);
            reply.writeInt32(start(in, caller));
            return 0;
        case kInjectMotion:
            injectMotion(in, caller);
            return 0;
        case kInjectKey:
            injectKey(in, caller);
            return 0;
        case kRelease:
            release(in, caller);
            reply.writeInt32(0);
            return 0;
        case kClose:
            close(in, caller);
            reply.writeInt32(0);
            return 0;
        case kExec:
            reply.writeInt32(0);
            exec(in, reply);
            return 0;
        default:
            return kUnknownTransaction;
    }
}

// ---- displays ----

int EmbedService::create(ParcelReader& in, const Caller& caller) {
    const int width = in.readInt32();
    const int height = in.readInt32();
    const int dpi = in.readInt32();
    const FlatObject client = in.readObject();
    // A window that opens but takes no touch would be worse than none: LX offers Android's window instead.
    if (!android_.windowsReady()) return kErrUnsupported;
    if (!in.ok() || client.type != kTypeHandle || !sizeAllowed(width, height, dpi)) return kErrArgument;
    {
        const std::lock_guard<std::mutex> guard(lock_);
        if (displays_.size() >= kMaxDisplays) return kErrLimit;
    }
    auto* callback = new DisplayCallback;
    // VirtualDisplayConfig, which LX's own framework wrote for this phone's Android, goes on unread.
    Parcel p = android_.begin(Call::CreateVirtualDisplay);
    p.splice(in, in.position(), in.size());
    callback->writeTo(p);
    p.writeNullBinder();  // no media projection
    p.writeString16(kShellPackage);
    Reply reply;
    const int status = android_.transact(Call::CreateVirtualDisplay, p, &reply);
    ParcelReader& r = reply.reader();
    const int exception = status == 0 ? r.readStatus() : 0;
    const int id = status == 0 && exception == 0 ? r.readInt32() : -1;
    if (id < 0) {
        callback->abandon();
        LOGW("display refused: status %d exception %d id %d", status, exception, id);
        return exception == kExceptionSecurity ? kErrDenied : kErrDisplay;
    }
    auto e = std::make_shared<Entry>();
    e->id = id;
    e->ownerUid = caller.uid;
    e->client = static_cast<uint32_t>(client.handle);
    e->callback = callback;
    e->width = width;
    e->height = height;
    Binder::get().acquire(e->client);
    e->death = Binder::get().linkToDeath(e->client, [this, id] {
        LOGI("LX died; releasing display %d", id);
        releaseDisplay(id);
    });
    keepAwake(*e);
    {
        const std::lock_guard<std::mutex> guard(lock_);
        displays_[id] = e;
    }
    setImePolicy(id);
    LOGI("display %d %dx%d@%d", id, width, height, dpi);
    return id;
}

void EmbedService::resize(ParcelReader& in, const Caller& caller) {
    const int displayId = in.readInt32();
    const int width = in.readInt32();
    const int height = in.readInt32();
    const int dpi = in.readInt32();
    if (!in.ok() || !sizeAllowed(width, height, dpi)) return;
    const EntryRef e = owned(displayId, caller);
    if (!e) return;
    Parcel p = android_.begin(Call::ResizeVirtualDisplay);
    e->callback->writeTo(p);
    p.writeInt32(width);
    p.writeInt32(height);
    p.writeInt32(dpi);
    Reply reply;
    android_.transact(Call::ResizeVirtualDisplay, p, &reply);
    e->width = width;
    e->height = height;
}

/**
 * A hidden window hands over no surface. Android turns a display off only when its Java client asks for that along with the
 * null surface, and this client does not, so the display stays on and draws nowhere: the app keeps running, and nothing
 * composes frames nobody sees.
 */
void EmbedService::setSurface(ParcelReader& in, const Caller& caller) {
    const int displayId = in.readInt32();
    const EntryRef e = owned(displayId, caller);
    if (!e || !in.ok()) return;
    {
        // A nullable Surface starts with 0 for none. Peeked, so the parcel goes on to Android whole.
        const size_t at = in.position();
        const bool shown = in.readInt32() != 0;
        in.seek(at);
        const std::lock_guard<std::mutex> guard(lock_);
        // A window that comes back reads the orientation once: the app may have turned while hidden.
        if (shown && !e->shown) e->nextLook = 0;
        e->shown = shown;
    }
    Parcel p = android_.begin(Call::SetVirtualDisplaySurface);
    e->callback->writeTo(p);
    p.splice(in, in.position(), in.size());
    Reply reply;
    android_.transact(Call::SetVirtualDisplaySurface, p, &reply);
    Parcel on = android_.begin(Call::SetVirtualDisplayState);
    e->callback->writeTo(on);
    on.writeInt32(1);
    Reply stateReply;
    android_.transact(Call::SetVirtualDisplayState, on, &stateReply);
}

/**
 * Nobody touches a hidden display, so Android would put it to sleep after the screen timeout and pause its app. This lock
 * holds that display's group alone, so the phone's own screen still goes off. No timeout: the lock lasts as long as the
 * window, and a dead daemon's lock ends with it.
 */
void EmbedService::keepAwake(Entry& e) {
    if (!android_.ready(Call::AcquireWakeLock)) return;
    auto* token = new Token;
    Parcel p = android_.begin(Call::AcquireWakeLock);
    token->writeTo(p);
    p.writeInt32(kScreenBrightWakeLock);
    p.writeString16("lx:display-" + std::to_string(e.id));
    p.writeString16(kShellPackage);
    p.writeInt32(0);  // no work source
    p.writeNullString16();  // no history tag
    p.writeInt32(e.id);
    p.writeNullBinder();  // no callback
    Reply reply;
    const int status = android_.transact(Call::AcquireWakeLock, p, &reply);
    if (status == 0 && reply.reader().readStatus() == 0) {
        e.awake = token;
    } else {
        LOGW("display %d has no wake lock", e.id);
        token->abandon();
    }
}

/**
 * The keyboard opens on the app's own display, inside the window. Only there does the app see it and move its text field
 * above it. With the phone's keyboard on the main display the box a WhatsApp message is typed into stayed under the keyboard.
 */
void EmbedService::setImePolicy(int displayId) {
    Parcel p = android_.begin(Call::SetDisplayImePolicy);
    p.writeInt32(displayId);
    p.writeInt32(kImePolicyLocal);
    Reply reply;
    android_.transact(Call::SetDisplayImePolicy, p, &reply);
}

EmbedService::EntryRef EmbedService::owned(int displayId, const Caller& caller) {
    const std::lock_guard<std::mutex> guard(lock_);
    const auto found = displays_.find(displayId);
    if (found == displays_.end() || found->second->ownerUid != caller.uid) return nullptr;
    return found->second;
}

void EmbedService::release(ParcelReader& in, const Caller& caller) {
    const int displayId = in.readInt32();
    if (in.ok() && owned(displayId, caller)) releaseDisplay(displayId);
}

/**
 * The window's close button: the app ends as if swiped out of Recents, then the display goes. Releasing the display alone
 * finished the app's activities but left its task in Recents and its process running.
 */
void EmbedService::close(ParcelReader& in, const Caller& caller) {
    const int displayId = in.readInt32();
    if (!in.ok() || !owned(displayId, caller)) return;
    for (int i = 0; i < kMaxTasksToRemove; i++) {
        const int task = firstTask(displayId);
        if (task <= kSomeTask) break;
        Parcel p = android_.begin(Call::RemoveTask);
        p.writeInt32(task);
        Reply reply;
        const int status = android_.transact(Call::RemoveTask, p, &reply);
        ParcelReader& r = reply.reader();
        if (status != 0 || r.readStatus() != 0 || r.readInt32() == 0) {
            LOGW("task %d on display %d stays: %d", task, displayId, status);
            break;
        }
        LOGI("removed task %d from display %d", task, displayId);
    }
    releaseDisplay(displayId);
}

void EmbedService::releaseDisplay(int displayId) {
    EntryRef e;
    {
        const std::lock_guard<std::mutex> guard(lock_);
        const auto found = displays_.find(displayId);
        if (found == displays_.end()) return;
        e = found->second;
        e->released = true;
        displays_.erase(found);
    }
    Binder& binder = Binder::get();
    binder.unlinkToDeath(e->death);
    Parcel p = android_.begin(Call::ReleaseVirtualDisplay);
    e->callback->writeTo(p);
    Reply reply;
    android_.transact(Call::ReleaseVirtualDisplay, p, &reply);
    if (e->awake != nullptr) {
        Parcel lock = android_.begin(Call::ReleaseWakeLock);
        e->awake->writeTo(lock);
        lock.writeInt32(0);
        Reply lockReply;
        android_.transact(Call::ReleaseWakeLock, lock, &lockReply);
        e->awake->abandon();
    }
    e->callback->abandon();
    binder.release(e->client);
    LOGI("released display %d", displayId);
}

void EmbedService::releaseAll() {
    std::vector<int> ids;
    {
        const std::lock_guard<std::mutex> guard(lock_);
        for (const auto& entry : displays_) ids.push_back(entry.first);
    }
    for (const int id : ids) releaseDisplay(id);
}

std::string EmbedService::describe() {
    std::string out;
    const std::lock_guard<std::mutex> guard(lock_);
    for (const auto& [id, e] : displays_) {
        out += "display " + std::to_string(id) + " " + std::to_string(e->width) + "x" + std::to_string(e->height) + " " +
               (e->package.empty() ? std::string("-") : e->package) + " task " + std::to_string(e->task) + " orientation " +
               std::to_string(e->orientation) + "\n";
    }
    if (out.empty()) out = "no LX windows\n";
    return out;
}

// ---- starting apps ----

bool EmbedService::userAllowed(int caller, int userId) {
    if (userId == caller) return true;
    // LX may start apps of its own user and of that user's work profile, which it lists anyway.
    Parcel p = android_.begin(Call::GetProfileIds);
    p.writeInt32(caller);
    p.writeInt32(1);  // enabled only
    Reply reply;
    if (android_.transact(Call::GetProfileIds, p, &reply) != 0) return false;
    ParcelReader& r = reply.reader();
    if (r.readStatus() != 0) return false;
    const int32_t count = r.readInt32();
    for (int32_t i = 0; i < count && r.ok(); i++) {
        if (r.readInt32() == userId) return true;
    }
    return false;
}

/**
 * The Intent and the options come as parcels LX's framework wrote: their fields change with every Android, and only that
 * framework knows this version's. LX takes both from an app it lists, so they name a launcher activity.
 */
int EmbedService::start(ParcelReader& in, const Caller& caller) {
    const int displayId = in.readInt32();
    const int userId = in.readInt32();
    const std::string package = in.readString16();
    const std::vector<uint8_t> intent = in.readByteArray();
    const std::vector<uint8_t> options = in.readByteArray();
    if (!android_.ready(Call::StartActivityAsUser)) return kErrUnsupported;
    if (!in.ok() || intent.empty() || options.empty()) return kErrArgument;
    const EntryRef e = owned(displayId, caller);
    if (!e) return kErrDisplay;
    if (!userAllowed(static_cast<int>(caller.uid) / kPerUserRange, userId)) return kErrUser;

    Parcel p = android_.begin(Call::StartActivityAsUser);
    p.writeNullBinder();  // no caller thread
    p.writeString16(kShellPackage);
    p.writeNullString16();  // no feature id
    p.writeInt32(1);
    p.writeBytes(intent.data(), intent.size());
    p.writeNullString16();  // no resolved type
    p.writeNullBinder();  // no result target
    p.writeNullString16();  // no result who
    p.writeInt32(0);  // request code
    p.writeInt32(0);  // flags
    p.writeInt32(0);  // no profiler
    p.writeInt32(1);
    p.writeBytes(options.data(), options.size());
    p.writeInt32(userId);
    Reply reply;
    const int status = android_.transact(Call::StartActivityAsUser, p, &reply);
    if (status != 0) {
        LOGW("start on %d failed: %d", displayId, status);
        return kErrFailed;
    }
    ParcelReader& r = reply.reader();
    const int exception = r.readStatus();
    if (exception != 0) {
        LOGW("start on %d: exception %d", displayId, exception);
        return exception == kExceptionSecurity ? kErrDenied : kErrFailed;
    }
    const int result = r.readInt32();
    LOGI("start %s on display %d user %d -> %d", package.c_str(), displayId, userId, result);
    if (result < 0) return result;
    {
        const std::lock_guard<std::mutex> guard(lock_);
        e->package = package;
        e->started = true;
        e->startedAt = nowMs();
        e->sawTask = false;
        e->emptyPolls = 0;
        e->reported = false;
        e->orientation = kOrientationUnknown;
        e->nextPoll = 0;
        e->nextLook = 0;
        e->lookEvery = kFirstLookMs;
        e->task = kNoTask;
    }
    watchers_.notify_all();
    return kOk;
}

// ---- input ----

void EmbedService::inject(const Parcel& event) {
    Reply reply;
    android_.transact(Call::InjectInputEvent, event, &reply);
}

void EmbedService::injectMotion(ParcelReader& in, const Caller& caller) {
    const int displayId = in.readInt32();
    if (in.readInt32() == 0 || !in.ok() || !owned(displayId, caller)) return;
    const size_t start = in.position();
    InputFields fields;
    if (!readMotionFields(in, &fields)) {
        LOGW("motion event of an unknown shape dropped");
        return;
    }
    const int action = fields.action & kMaskAction;
    if (action == kMotionDown || action == kMotionUp) focus(displayId);
    Parcel p = android_.begin(Call::InjectInputEvent);
    p.writeInt32(1);
    const size_t base = p.size();
    p.splice(in, start, in.size());
    p.patchInt32(base + fields.displayIdAt - start, displayId);
    p.writeInt32(kInjectAsync);
    inject(p);
}

void EmbedService::injectKey(ParcelReader& in, const Caller& caller) {
    const int displayId = in.readInt32();
    if (in.readInt32() == 0 || !in.ok() || !owned(displayId, caller)) return;
    const size_t start = in.position();
    InputFields fields;
    if (!readKeyFields(in, &fields)) {
        LOGW("key event of an unknown shape dropped");
        return;
    }
    if (!keyAllowed(fields.keyCode, fields.metaState, fields.action)) {
        LOGW("key %d meta 0x%x refused on %d", fields.keyCode, fields.metaState, displayId);
        return;
    }
    Parcel p = android_.begin(Call::InjectInputEvent);
    p.writeInt32(1);
    const size_t base = p.size();
    p.splice(in, start, in.size());
    p.patchInt32(base + fields.displayIdAt - start, displayId);
    p.writeInt32(kInjectAsync);
    inject(p);
}

/**
 * Gives the display's app the phone's focus, and with it the phone's keyboard: Android shows the keyboard only for the
 * display with the top focus, and a tap in the window first lands on LX, whose display then takes that focus. Done for each
 * press and release, after Android has moved the focus to LX for it.
 *
 * The new focus reaches the input system a frame later. The press that follows waits for it: an app that got the release
 * before it knew it had focus asked for the keyboard too early, and Android turned the request down.
 */
void EmbedService::focus(int displayId) {
    const int task = firstTask(displayId);
    if (task <= kSomeTask) return;
    Parcel p = android_.begin(Call::SetFocusedTask);
    p.writeInt32(task);
    Reply reply;
    android_.transact(Call::SetFocusedTask, p, &reply);
    Parcel sync = android_.begin(Call::SyncInputTransactions);
    sync.writeInt32(0);  // no need to wait for animations
    Reply synced;
    android_.transact(Call::SyncInputTransactions, sync, &synced);
}

int EmbedService::firstTask(int displayId) {
    Parcel p = android_.begin(Call::GetAllRootTaskInfosOnDisplay);
    p.writeInt32(displayId);
    Reply reply;
    if (android_.transact(Call::GetAllRootTaskInfosOnDisplay, p, &reply) != 0) return kNoAnswer;
    ParcelReader& r = reply.reader();
    if (r.readStatus() != 0) return kNoAnswer;
    const int32_t tasks = r.readInt32();
    if (!r.ok()) return kNoAnswer;
    if (tasks <= 0) return kNoTask;
    // RootTaskInfo starts with its bounds (a typed Rect) and the ids of its tasks; the rest changes between versions.
    // Whatever follows, the list says the display has a task, and that is what decides whether its window stays.
    if (r.readInt32() == 0) return kSomeTask;
    if (r.readInt32() != 0) r.seek(r.position() + 4 * sizeof(int32_t));
    const int32_t children = r.readInt32();
    if (!r.ok() || children <= 0) return kSomeTask;
    const int32_t first = r.readInt32();
    return r.ok() && first > 0 ? first : kSomeTask;
}

// ---- watching the apps ----

void EmbedService::startWatching() {
    // Registered before the threads start, which read listener_ without a lock. Lives as long as this process. Without
    // it the dump reads back off on their own and the slow poll still notices an app that ended.
    if (android_.ready(Call::RegisterTaskStackListener)) {
        auto* listener = new TaskListener(*this, android_.taskListenerCode("onActivityRequestedOrientationChanged"),
                                          android_.taskListenerCode("onTaskRequestedOrientationChanged"),
                                          android_.taskListenerCode("onTaskMovedToFront"),
                                          android_.taskListenerCode("onTaskRemoved"));
        Parcel p = android_.begin(Call::RegisterTaskStackListener);
        listener->writeTo(p);
        Reply reply;
        const int status = android_.transact(Call::RegisterTaskStackListener, p, &reply);
        if (status == 0 && reply.reader().readStatus() == 0) listener_ = listener;
        else LOGW("no task events: %d", status);
    }
    watcher_ = std::thread([this] { watch(); });
    looker_ = std::thread([this] { look(); });
    watcher_.detach();
    looker_.detach();
}

void EmbedService::ring(int taskId) {
    {
        const std::lock_guard<std::mutex> guard(lock_);
        for (const auto& entry : displays_) {
            const EntryRef& e = entry.second;
            // A display whose task id this Android does not give away (kSomeTask) could be the one.
            if (taskId == kNoTask || e->task == taskId || e->task == kSomeTask) {
                e->nextLook = 0;
                e->nextPoll = 0;
            }
        }
    }
    watchers_.notify_all();
}

void EmbedService::orientationAsked(int taskId, int orientation) {
    std::vector<EntryRef> changed;
    bool unknown = false;
    {
        const std::lock_guard<std::mutex> guard(lock_);
        for (const auto& entry : displays_) {
            const EntryRef& e = entry.second;
            if (e->released || !e->started) continue;
            if (e->task == taskId && taskId > 0) {
                if (e->orientation != orientation) {
                    e->orientation = orientation;
                    changed.push_back(e);
                }
            } else if (e->task == kSomeTask || e->task == kNoTask) {
                unknown = true;
            }
        }
    }
    for (const EntryRef& e : changed) reportOrientation(*e, orientation);
    // A display whose task is not known yet reads the dump to find out.
    if (unknown) ring(kSomeTask);
}

void EmbedService::taskGone(int taskId) {
    {
        const std::lock_guard<std::mutex> guard(lock_);
        for (const auto& entry : displays_) {
            const EntryRef& e = entry.second;
            if (e->task == taskId || e->task == kSomeTask) e->nextPoll = 0;
        }
    }
    watchers_.notify_all();
}

/**
 * Tells LX when nothing is left on a display: the app finished (LX closes the window) or never arrived. Each display has its
 * own time for the next question (pollDelay); the task events wake the watcher sooner.
 */
void EmbedService::watch() {
    std::unique_lock<std::mutex> guard(lock_);
    for (;;) {
        const auto watched = [this] {
            return std::any_of(displays_.begin(), displays_.end(), [](const auto& d) { return d.second->started && !d.second->reported; });
        };
        watchers_.wait(guard, watched);
        const int64_t now = nowMs();
        std::vector<EntryRef> due;
        for (const auto& entry : displays_) {
            const EntryRef& e = entry.second;
            if (e->started && !e->reported && e->nextPoll <= now) due.push_back(e);
        }
        guard.unlock();
        for (const EntryRef& e : due) pollOnce(e, now);
        guard.lock();
        int64_t wait = kSteadyPollMs;
        const int64_t after = nowMs();
        for (const auto& entry : displays_) {
            const EntryRef& e = entry.second;
            if (e->started && !e->reported) wait = std::min(wait, std::max<int64_t>(0, e->nextPoll - after));
        }
        if (wait > 0) watchers_.wait_for(guard, std::chrono::milliseconds(wait));
    }
}

void EmbedService::pollOnce(const EntryRef& e, int64_t now) {
    const int task = firstTask(e->id);
    bool report = false;
    bool look = false;
    {
        const std::lock_guard<std::mutex> guard(lock_);
        if (e->released) return;
        if (task == kNoAnswer) {
            e->nextPoll = now + pollDelay(e->sawTask, 0);
            return;
        }
        if (task >= 0) {
            e->sawTask = true;
            e->emptyPolls = 0;
            e->task = task;
            if (!e->looking && e->shown && now >= e->nextLook) {
                e->looking = true;
                look = true;
            }
        } else {
            e->emptyPolls++;
            report = e->sawTask ? e->emptyPolls >= kEmptyPolls : now - e->startedAt > kArrivalTimeoutMs;
            if (report) e->reported = true;
        }
        e->nextPoll = now + pollDelay(e->sawTask, e->emptyPolls);
    }
    if (report) reportEmpty(*e);
    if (look) {
        {
            const std::lock_guard<std::mutex> guard(lookLock_);
            lookQueue_.push_back(e);
        }
        lookers_.notify_one();
    }
}

void EmbedService::reportEmpty(const Entry& e) {
    LOGI("display %d is empty (had a task: %d)", e.id, e.sawTask ? 1 : 0);
    Parcel p;
    p.writeInterfaceToken(kClient);
    p.writeInt32(e.id);
    p.writeInt32(e.sawTask ? 1 : 0);
    Binder::get().transact(e.client, kOnDisplayEmpty, p, nullptr, kOneway);
}

void EmbedService::reportOrientation(const Entry& e, int orientation) {
    LOGI("display %d asks for orientation %d", e.id, orientation);
    Parcel p;
    p.writeInterfaceToken(kClient);
    p.writeInt32(e.id);
    p.writeInt32(orientation);
    Binder::get().transact(e.client, kOnOrientation, p, nullptr, kOneway);
}

/** Reads Android's dump on a thread of its own, so a slow dump keeps the watcher from noticing that an app ended. */
void EmbedService::look() {
    for (;;) {
        EntryRef e;
        {
            std::unique_lock<std::mutex> guard(lookLock_);
            lookers_.wait(guard, [this] { return !lookQueue_.empty(); });
            e = lookQueue_.front();
            lookQueue_.pop_front();
        }
        lookAt(e);
    }
}

/**
 * Passes on the orientation the app asks for. The display cannot turn with the app, so LX gives it the shape the app wants:
 * a portrait-only app on a wide display gets black bars, and YouTube asks for landscape when a video goes full screen. A
 * display held in a shape is looked at often, to see the app let go of it; any other waits longer each time.
 */
void EmbedService::lookAt(const EntryRef& e) {
    char quoted[512];
    shellQuote(e->package, quoted, sizeof(quoted));
    std::string dump;
    runCaptured(std::string("dumpsys activity -a -p ") + quoted + " activities", {}, kDumpTimeoutMs, kDumpMax, &dump);
    const std::optional<int> orientation = requestedOrientation(dump, e->id);
    bool changed = false;
    {
        const std::lock_guard<std::mutex> guard(lock_);
        const bool same = !orientation || *orientation == e->orientation;
        e->lookEvery = lookDelay(e->lookEvery, !same);
        // With the orientation events, a known task is read once and then only when an event asks for it.
        const bool heard = listener_ != nullptr && listener_->hearsOrientation() && e->task > 0;
        e->nextLook = heard ? kNever : nowMs() + e->lookEvery;
        e->looking = false;
        if (!same) {
            e->orientation = *orientation;
            changed = !e->released;
        }
    }
    if (changed) reportOrientation(*e, *orientation);
}

// ---- running commands ----

void EmbedService::exec(ParcelReader& in, Parcel& reply) {
    const std::string command = in.readString16();
    const std::vector<uint8_t> input = in.readByteArray();
    const int timeoutMs = std::clamp(in.readInt32(), 1, kExecTimeoutMaxMs);
    if (!in.ok()) {
        reply.writeInt32(-1);
        return;
    }
    std::string output;
    const int exit = runCaptured(command, {reinterpret_cast<const char*>(input.data()), input.size()}, timeoutMs, kExecMaxOutput, &output);
    // The exit status, then what the command wrote.
    std::vector<uint8_t> bytes(sizeof(int32_t) + output.size());
    for (size_t i = 0; i < sizeof(int32_t); i++) bytes[i] = static_cast<uint8_t>(static_cast<uint32_t>(exit) >> (24 - 8 * i));
    std::copy(output.begin(), output.end(), bytes.begin() + sizeof(int32_t));
    reply.writeByteArray(bytes);
}

}  // namespace lxdb
