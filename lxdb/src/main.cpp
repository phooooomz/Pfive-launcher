// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// lxdb: what adb does on this phone, from this phone. The daemon runs as the shell user, talks to Android through
// /dev/binder and to LX through a binder of its own, and goes without a network or a USB cable.
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "android.hpp"
#include "binder.hpp"
#include "control.hpp"
#include "embed_service.hpp"
#include "process.hpp"
#include "rules.hpp"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "lxdb", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "lxdb", __VA_ARGS__)

namespace lxdb {

namespace {

constexpr char kVersion[] = "1.0";
constexpr const char* kProvider = "android.content.IContentProvider";
constexpr const char* kShellPackage = "com.android.shell";
constexpr const char* kAttachMethod = "attach";
constexpr const char* kKeyToken = "token";
constexpr const char* kKeyService = "service";
constexpr const char* kKeyApk = "apk";
constexpr int kLoopers = 4;
constexpr int kRetrySeconds = 2;
constexpr int kPatientRetrySeconds = 30;
constexpr int kQuickFailures = 60;
constexpr size_t kTokenSize = 32;
constexpr int kExitUsage = 2;
constexpr int kExitService = 3;

/** What LX told the daemon about itself on its command line. */
struct Start {
    std::string token;
    int userId = 0;
    std::string package;
    std::string apk;
};

enum class Handoff { Attached, Refused, Failed };

bool isToken(const std::string& s) {
    if (s.size() != kTokenSize) return false;
    for (const char c : s) {
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    }
    return true;
}

int packageUid(Android& android, const std::string& package, int userId) {
    Parcel p = android.begin(Call::GetPackageUid);
    p.writeString16(package);
    p.writeInt64(0);
    p.writeInt32(userId);
    Reply reply;
    if (android.transact(Call::GetPackageUid, p, &reply) != 0) return -1;
    ParcelReader& r = reply.reader();
    if (r.readStatus() != 0) return -1;
    const int uid = r.readInt32();
    return r.ok() ? uid : -1;
}

/** AttributionSource for this process, field by field as AttributionSourceState.writeToParcel lays it out. */
void writeSource(Parcel& p, Android& android, Node& token) {
    const size_t start = p.size();
    p.writeInt32(0);  // its size, known at the end
    p.writeInt32(-1);  // pid
    p.writeInt32(static_cast<int32_t>(getuid()));
    if (android.sourceHasDeviceId()) p.writeInt32(0);
    p.writeString16(getuid() == kRootUid ? "root" : kShellPackage);
    p.writeNullString16();  // no attribution tag
    token.writeTo(p);
    p.writeInt32(-1);  // no renounced permissions
    p.writeInt32(0);  // no next source
    p.patchInt32(start, static_cast<int32_t>(p.size() - start));
}

/**
 * Gives [service] to LX: the system's own way to reach a provider from outside an app, as the content command does. LX answers
 * with a binder of its own process. The answer must come from LX's uid, which the kernel vouches for.
 */
Handoff handOff(Android& android, Node& service, Node& external, const Start& start, int lxUid, uint32_t* lxHandle) {
    const std::string authority = start.package + ".embed";
    Parcel get = android.begin(Call::GetContentProviderExternal);
    get.writeString16(authority);
    get.writeInt32(start.userId);
    external.writeTo(get);
    get.writeString16("lxdb");
    Reply holder;
    if (android.transact(Call::GetContentProviderExternal, get, &holder) != 0) return Handoff::Failed;
    ParcelReader& h = holder.reader();
    if (h.readStatus() != 0 || h.readInt32() == 0 || !h.ok()) return Handoff::Failed;
    // ContentProviderHolder: the provider is the first binder in it. ProviderInfo before it holds none.
    const FlatObject provider = h.objectAt(0);
    if (provider.type != kTypeHandle) return Handoff::Failed;

    Parcel call;
    call.writeInterfaceToken(kProvider);
    writeSource(call, android, external);
    call.writeString16(authority);
    call.writeString16(kAttachMethod);
    call.writeNullString16();
    writeBundle(call, {{kKeyToken, start.token, nullptr}, {kKeyService, {}, &service}, {kKeyApk, start.apk, nullptr}});
    Reply answer;
    Handoff result = Handoff::Failed;
    if (Binder::get().transact(static_cast<uint32_t>(provider.handle), android.providerCall(), call, &answer) == 0) {
        ParcelReader& r = answer.reader();
        if (static_cast<int>(answer.senderUid()) != lxUid) {
            LOGW("the provider answered from uid %u, not %d", answer.senderUid(), lxUid);
            result = Handoff::Refused;
        } else if (r.readStatus() == 0) {
            const FlatObject lx = r.objectAt(0);
            result = lx.type == kTypeHandle ? Handoff::Attached : Handoff::Refused;
            if (result == Handoff::Attached) {
                *lxHandle = static_cast<uint32_t>(lx.handle);
                Binder::get().acquire(*lxHandle);
            }
        }
    }
    Parcel done = android.begin(Call::RemoveContentProviderExternalAsUser);
    done.writeString16(authority);
    external.writeTo(done);
    done.writeInt32(start.userId);
    Reply released;
    android.transact(Call::RemoveContentProviderExternalAsUser, done, &released);
    return result;
}

std::string property(const char* name) {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get(name, value);
    return value;
}

std::string statusText(const Android& android, int lxAppId) {
    std::string text = "daemon pid " + std::to_string(getpid()) + " uid " + std::to_string(getuid());
    text += "\nLX app id " + std::to_string(lxAppId);
    text += "\nwindows ";
    text += android.windowsReady() ? "ready\n" : "not ready on this Android, see lxdb doctor\n";
    return text;
}

[[noreturn]] void quit(EmbedService& service, const char* why) {
    LOGI("exiting: %s", why);
    service.releaseAll();
    _exit(0);
}

/**
 * Hands the service to LX and to the LX process after it, for as long as the phone is up. Starting this daemon again takes a
 * network, so it waits out an LX that is away for hours, and asking for LX's provider brings a stopped LX back. Only an LX that
 * refuses it, as a new LX does after an update, or that was removed, ends it.
 */
void stay(Android& android, EmbedService& service, Node& external, const Start& start, int lxUid) {
    int failures = 0;
    for (;;) {
        uint32_t lx = 0;
        const Handoff result = handOff(android, service, external, start, lxUid, &lx);
        if (result == Handoff::Refused) quit(service, "LX turned this daemon away");
        if (result == Handoff::Failed) {
            if (packageUid(android, start.package, start.userId) != lxUid) quit(service, "LX is no longer installed");
            const bool quick = ++failures < kQuickFailures;
            std::this_thread::sleep_for(std::chrono::seconds(quick ? kRetrySeconds : kPatientRetrySeconds));
            continue;
        }
        failures = 0;
        LOGI("attached");
        std::mutex lock;
        std::condition_variable ended;
        bool gone = false;
        Death* death = Binder::get().linkToDeath(lx, [&] {
            const std::lock_guard<std::mutex> guard(lock);
            gone = true;
            ended.notify_all();
        });
        {
            std::unique_lock<std::mutex> guard(lock);
            ended.wait(guard, [&] { return gone; });
        }
        LOGI("LX ended");
        Binder::get().unlinkToDeath(death);
        Binder::get().release(lx);
        service.releaseAll();
        std::this_thread::sleep_for(std::chrono::seconds(kRetrySeconds));
    }
}

/**
 * Leaves the adb session behind, so the command returns at once and the daemon goes on after adb hangs up.
 *
 * LX's session is a terminal (adbd gives one to every command run without its shell protocol), and when the command's
 * first process exits the kernel hangs up on its whole group. A child that had not left the group by then died without a
 * word: LX reported the daemon started and waited for nothing, which happened on a busy phone, after a restart above all.
 * So the hangup is ignored from before the fork, and the parent returns only once the child is in a session of its own.
 */
void detach() {
    signal(SIGHUP, SIG_IGN);
    int left[2];
    const bool told = pipe2(left, O_CLOEXEC) == 0;
    const pid_t pid = fork();
    if (pid < 0) _exit(kExitService);
    if (pid > 0) {
        if (told) {
            close(left[1]);
            char byte;
            // A child that dies first closes its end, which ends the wait as well.
            while (read(left[0], &byte, 1) < 0 && errno == EINTR) {
            }
        }
        _exit(0);
    }
    setsid();
    if (told) {
        close(left[0]);
        const char byte = 1;
        while (write(left[1], &byte, 1) < 0 && errno == EINTR) {
        }
        close(left[1]);
    }
    const int null = open("/dev/null", O_RDWR | O_CLOEXEC);
    if (null >= 0) {
        dup2(null, STDIN_FILENO);
        dup2(null, STDOUT_FILENO);
        dup2(null, STDERR_FILENO);
    }
    signal(SIGPIPE, SIG_IGN);
    prctl(PR_SET_NAME, "lxdbd", 0, 0, 0);
}

int runDaemon(const Start& start) {
    if (!isToken(start.token) || start.userId < 0) return kExitUsage;
    detach();
    // On the heap, because threads that use them outlive main's scope in every exit path but _exit.
    auto* android = new Android;
    std::string why;
    if (!android->open(&why)) {
        LOGW("%s", why.c_str());
        return kExitService;
    }
    const int lxUid = packageUid(*android, start.package, start.userId);
    if (lxUid < 0) {
        LOGW("%s is not installed for user %d", start.package.c_str(), start.userId);
        return kExitService;
    }
    const int lxAppId = lxUid % kPerUserRange;
    const int listenFd = bindControl(start.userId);
    if (listenFd < 0) {
        LOGW("the control socket stays taken");
        return kExitService;
    }
    auto* service = new EmbedService(*android, lxAppId);
    LOGI("up: pid %d uid %d sdk %s lx app %d", getpid(), getuid(), property("ro.build.version.sdk").c_str(), lxAppId);
    Binder::get().startLoopers(kLoopers);
    service->startWatching();
    android->watchSystem([service] { quit(*service, "system_server ended"); });

    auto* external = new Token;
    std::thread([android, service, external, start, lxUid] { stay(*android, *service, *external, start, lxUid); }).detach();

    ControlHandler handler;
    handler.status = [android, lxAppId] { return statusText(*android, lxAppId); };
    handler.doctor = [android] { return android->report(); };
    handler.windows = [service] { return service->describe(); };
    handler.end = [service] { quit(*service, "asked to stop"); };
    serveControl(listenFd, std::move(handler));
    return 0;
}

// ---- commands ----

int usage() {
    fputs("usage: lxdb <command>\n"
          "  version                 this tool and the daemon\n"
          "  devices                 this phone\n"
          "  doctor                  what this phone's Android lets lxdb call\n"
          "  services                Android's Binder services\n"
          "  windows                 LX's app windows\n"
          "  shell [command]         a shell as the shell user\n"
          "  push <file> <path>      copy a file to a path the shell can write\n"
          "  pull <path> <file>      copy a file from a path the shell can read\n"
          "  install <apk>           install an app\n"
          "  uninstall <package>     remove an app\n"
          "  logcat [arguments]      the system log\n"
          "  dumpsys [arguments]     Android's service dumps\n"
          "  stop                    end the daemon and LX's app windows\n",
          stderr);
    return kExitUsage;
}

std::string quoted(const std::string& text) {
    std::vector<char> out(text.size() * 4 + 3);
    shellQuote(text, out.data(), out.size());
    return out.data();
}

std::string joined(int argc, char** argv, int from) {
    std::string out;
    for (int i = from; i < argc; i++) {
        if (i > from) out += ' ';
        out += argv[i];
    }
    return out;
}

int currentUser() { return static_cast<int>(getuid()) / kPerUserRange; }

int fileError(const char* path) {
    fprintf(stderr, "lxdb: %s: %s\n", path, strerror(errno));
    return 1;
}

int doctor() {
    Android android;
    std::string why;
    if (!android.open(&why)) {
        fprintf(stderr, "lxdb: %s\n", why.c_str());
        return kExitService;
    }
    fputs(android.report().c_str(), stdout);
    return android.windowsReady() ? 0 : 1;
}

int services() {
    Android android;
    std::string why;
    if (!android.open(&why)) {
        fprintf(stderr, "lxdb: %s\n", why.c_str());
        return kExitService;
    }
    for (const std::string& name : android.listServices()) puts(name.c_str());
    return 0;
}

int devices() {
    printf("%s\tlocal %s %s Android %s sdk %s\n", property("ro.serialno").c_str(), property("ro.product.manufacturer").c_str(),
           property("ro.product.model").c_str(), property("ro.build.version.release").c_str(), property("ro.build.version.sdk").c_str());
    return 0;
}

int runCommand(int argc, char** argv) {
    if (argc < 2) return usage();
    const std::string verb = argv[1];
    const int user = currentUser();
    if (verb == "daemon") {
        if (argc != 6) return usage();
        Start start;
        start.token = argv[2];
        start.userId = atoi(argv[3]);
        start.package = argv[4];
        start.apk = argv[5];
        return runDaemon(start);
    }
    if (verb == "version") {
        printf("lxdb %s\n", kVersion);
        return askDaemon(user, "status");
    }
    if (verb == "devices") return devices();
    if (verb == "doctor") return doctor();
    if (verb == "services") return services();
    if (verb == "windows" || verb == "stop") return askDaemon(user, verb);
    if (verb == "shell") {
        const std::string command = joined(argc, argv, 2);
        if (isatty(STDIN_FILENO) || command.empty()) return terminalOnDaemon(user, command);
        return execOnDaemon(user, command, STDIN_FILENO, STDOUT_FILENO, STDERR_FILENO);
    }
    if (verb == "logcat" || verb == "dumpsys") {
        return execOnDaemon(user, verb + " " + joined(argc, argv, 2), STDIN_FILENO, STDOUT_FILENO, STDERR_FILENO);
    }
    if (verb == "push" && argc == 4) {
        const int file = open(argv[2], O_RDONLY | O_CLOEXEC);
        if (file < 0) return fileError(argv[2]);
        const int status = execOnDaemon(user, "cat > " + quoted(argv[3]), file, STDOUT_FILENO, STDERR_FILENO);
        close(file);
        return status;
    }
    if (verb == "pull" && argc == 4) {
        const int file = open(argv[3], O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        if (file < 0) return fileError(argv[3]);
        const int status = execOnDaemon(user, "cat " + quoted(argv[2]), STDIN_FILENO, file, STDERR_FILENO);
        close(file);
        return status;
    }
    if (verb == "install" && argc == 3) {
        const int file = open(argv[2], O_RDONLY | O_CLOEXEC);
        if (file < 0) return fileError(argv[2]);
        struct stat st {};
        fstat(file, &st);
        const int status = execOnDaemon(user, "pm install -r -S " + std::to_string(st.st_size), file, STDOUT_FILENO, STDERR_FILENO);
        close(file);
        return status;
    }
    if (verb == "uninstall" && argc == 3) {
        return execOnDaemon(user, "pm uninstall " + quoted(argv[2]), STDIN_FILENO, STDOUT_FILENO, STDERR_FILENO);
    }
    return usage();
}

}  // namespace

}  // namespace lxdb

int main(int argc, char** argv) { return lxdb::runCommand(argc, argv); }
