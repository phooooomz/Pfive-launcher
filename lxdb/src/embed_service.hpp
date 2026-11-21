// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// The service behind LX frames for Android apps, the far side of IEmbedService.aidl. It runs as the shell user and calls
// Android directly: displays, activities, input and focus for those displays. Only LX's own uid may call it.
#pragma once

#include <condition_variable>
#include <cstdint>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include "android.hpp"
#include "binder.hpp"

namespace lxdb {

class EmbedService : public Node {
public:
    EmbedService(Android& android, int lxAppId);

    std::string_view descriptor() const override;
    int onTransact(uint32_t code, ParcelReader& in, Parcel& reply, const Caller& caller) override;

    /** Removes every display, and with them the apps on them. */
    void releaseAll();
    /** One line per display, for `lxdb windows`. */
    std::string describe();
    void startWatching();
    /** Something changed in an app's wishes: looks at [taskId]'s display now. Any id looks at every display. */
    void ring(int taskId);

private:
    class TaskListener;
    struct Entry;
    using EntryRef = std::shared_ptr<Entry>;

    int create(ParcelReader& in, const Caller& caller);
    void resize(ParcelReader& in, const Caller& caller);
    void setSurface(ParcelReader& in, const Caller& caller);
    int start(ParcelReader& in, const Caller& caller);
    void injectMotion(ParcelReader& in, const Caller& caller);
    void injectKey(ParcelReader& in, const Caller& caller);
    void release(ParcelReader& in, const Caller& caller);
    void close(ParcelReader& in, const Caller& caller);
    void exec(ParcelReader& in, Parcel& reply);

    EntryRef owned(int displayId, const Caller& caller);
    void releaseDisplay(int displayId);
    void keepAwake(Entry& e);
    void setImePolicy(int displayId);
    bool userAllowed(int caller, int userId);
    void inject(const Parcel& event);
    void focus(int displayId);
    /** The first task on the display: -1 for none, -2 when Android did not answer. */
    int firstTask(int displayId);

    void watch();
    void pollOnce(const EntryRef& e, int64_t now);
    void reportEmpty(const Entry& e);
    void reportOrientation(const Entry& e, int orientation);
    void look();
    void lookAt(const EntryRef& e);
    /** A task event: [taskId] asks for [orientation] (portrait, landscape or unspecified). */
    void orientationAsked(int taskId, int orientation);
    /** A task event: [taskId] is gone, so its display may be empty. */
    void taskGone(int taskId);

    Android& android_;
    const int lxAppId_;
    std::mutex lock_;
    std::map<int, EntryRef> displays_;
    std::condition_variable watchers_;
    std::thread watcher_;
    std::mutex lookLock_;
    std::condition_variable lookers_;
    std::deque<EntryRef> lookQueue_;
    std::thread looker_;
    TaskListener* listener_ = nullptr;
};

}  // namespace lxdb
