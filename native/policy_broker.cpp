// SPDX-License-Identifier: GPL-3.0-only
#include "policy_broker.hpp"
#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <iostream>
#include <mutex>
#include <poll.h>
#include <set>
#include <sstream>
#include <sys/file.h>
#include <sys/inotify.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/xattr.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace sfpolicy {
namespace {
constexpr const char* kStateDirectory = "/data/adb/enable_screenshot17fix";
volatile sig_atomic_t stopping = 0;
void stopSignal(int) { stopping = 1; }
struct FatalTrace : std::runtime_error { using std::runtime_error::runtime_error; };
struct Fd {
    int fd = -1;
    explicit Fd(int n = -1) : fd(n) {}
    ~Fd() { if (fd >= 0) close(fd); }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
};
using Clock = std::chrono::steady_clock;
std::string q(const std::string& value) {
    std::string out = "\"";
    for (unsigned char c : value) { if (c == '"' || c == '\\') out += '\\'; out += c >= 32 && c < 127 ? char(c) : '?'; }
    return out + '"';
}
bool atom(const std::string& value) {
    return value.size() >= 16 && value.size() <= 96 && std::all_of(value.begin(), value.end(), [](char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    });
}
std::string canonicalDirectory(const std::string& path, bool rootOwned) {
    std::array<char, 4096> resolved{};
    if (!realpath(path.c_str(), resolved.data())) throw std::runtime_error("directory_unavailable");
    struct stat st{};
    if (lstat(resolved.data(), &st) || !S_ISDIR(st.st_mode) || (st.st_mode & 002) ||
        ((st.st_mode & 020) && (rootOwned || st.st_gid != st.st_uid)) ||
        (rootOwned ? st.st_uid != 0 : st.st_uid == 0)) throw std::runtime_error("directory_identity_mismatch");
    return resolved.data();
}
std::string privateText(const std::string& path, uid_t owner, size_t limit, bool missingAllowed = false) {
    Fd fd(open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (fd.fd < 0) { if (missingAllowed && errno == ENOENT) return {}; throw std::runtime_error("private_file_unavailable"); }
    struct stat st{};
    if (fstat(fd.fd, &st) || !S_ISREG(st.st_mode) || st.st_uid != owner || (st.st_mode & 077) ||
        st.st_size < 0 || uint64_t(st.st_size) > limit) throw std::runtime_error("private_file_identity_mismatch");
    std::string text(size_t(st.st_size), '\0'); size_t done = 0;
    while (done < text.size()) {
        ssize_t n = read(fd.fd, text.data() + done, text.size() - done);
        if (n <= 0) throw std::runtime_error("private_file_read_failed");
        done += size_t(n);
    }
    return text;
}
uint64_t processStart(pid_t pid) {
    std::ifstream file("/proc/" + std::to_string(pid) + "/stat"); std::string line;
    std::getline(file, line); auto end = line.rfind(')');
    if (end == std::string::npos) throw std::runtime_error("peer_identity_unavailable");
    std::istringstream fields(line.substr(end + 2)); std::string word;
    for (int i = 3; i <= 22; ++i) if (!(fields >> word)) throw std::runtime_error("peer_identity_unavailable");
    return std::stoull(word);
}
std::set<uid_t> hostUids(const std::string& module) {
    auto text = privateText(module + "/policy-system-uids.conf", 0, 8192, true);
    std::set<uid_t> result{1000}; std::istringstream lines(text); std::string line; size_t count = 0;
    while (std::getline(lines, line)) {
        if (line.empty()) continue;
        if (++count > 1024 || line.size() > 10 || !std::all_of(line.begin(), line.end(), [](char c) { return c >= '0' && c <= '9'; }))
            throw std::runtime_error("host_uid_config_invalid");
        uint64_t uid = std::stoull(line);
        if (uid < 1000 || uid > INT32_MAX) throw std::runtime_error("host_uid_config_invalid");
        result.insert(uid_t(uid));
    }
    return result;
}
bool desired(const std::string& file, uid_t appUid) {
    auto text = privateText(file, appUid, 4, true);
    while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) text.pop_back();
    if (text.empty()) {
        struct stat st{};
        if (lstat(file.c_str(), &st) && errno == ENOENT) return false;
        throw std::runtime_error("desired_config_invalid");
    }
    if (text == "0") return false;
    if (text == "1") return true;
    throw std::runtime_error("desired_config_invalid");
}
void publish(const std::string& directory, const std::string& name, const std::string& data, uid_t uid, gid_t gid, bool copyLabel) {
    Fd folder(open(directory.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    struct stat folderStat{};
    if (folder.fd < 0 || fstat(folder.fd, &folderStat) || !S_ISDIR(folderStat.st_mode) || folderStat.st_uid != uid)
        throw std::runtime_error("status_publish_directory_changed");
    static uint64_t sequence = 0;
    std::string temporary = name + ".broker." + std::to_string(getpid()) + '.' + std::to_string(++sequence);
    Fd file(openat(folder.fd, temporary.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
    if (file.fd < 0) throw std::runtime_error("status_publish_open_failed");
    struct Pending {
        int folder; const std::string& name;
        ~Pending() { unlinkat(folder, name.c_str(), 0); }
    } pending{folder.fd, temporary};
    struct stat st{};
    if (fstat(file.fd, &st) || !S_ISREG(st.st_mode) || fchmod(file.fd, 0600) || fchown(file.fd, uid, gid))
        throw std::runtime_error("status_publish_identity_failed");
    if (copyLabel) {
        std::array<char, 512> label{};
        ssize_t n = fgetxattr(folder.fd, "security.selinux", label.data(), label.size());
        if (n <= 0 || fsetxattr(file.fd, "security.selinux", label.data(), size_t(n), 0))
            throw std::runtime_error("status_publish_label_failed");
    }
    std::string line = data + '\n'; size_t done = 0;
    while (done < line.size()) {
        ssize_t n = write(file.fd, line.data() + done, line.size() - done);
        if (n <= 0) throw std::runtime_error("status_publish_write_failed");
        done += size_t(n);
    }
    if (renameat(folder.fd, temporary.c_str(), folder.fd, name.c_str())) throw std::runtime_error("status_publish_rename_failed");
}
bool sendLine(int fd, const std::string& value) {
    std::string data = value + '\n'; size_t done = 0; auto deadline = Clock::now() + std::chrono::milliseconds(1000);
    while (done < data.size()) {
        ssize_t n = send(fd, data.data() + done, data.size() - done, MSG_NOSIGNAL);
        if (n > 0) { done += size_t(n); continue; }
        if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) && Clock::now() < deadline) {
            pollfd p{fd, POLLOUT, 0}; poll(&p, 1, 20); continue;
        }
        return false;
    }
    return true;
}
struct Client {
    uint64_t id = 0, start = 0;
    ucred peer{};
    std::string input;
    Clock::time_point partialSince{};
};

// This runs only while the first real native discovery/verification is in
// progress. No heartbeat thread remains once the verified states are published.
class MatchingProgress {
    std::mutex mutex;
    std::condition_variable changed;
    bool finished = false;
    std::thread worker;
public:
    MatchingProgress(const std::string& app, const std::string& module, uid_t uid, gid_t gid, bool arbitration) {
        auto heartbeat = [app, module, uid, gid, arbitration] {
            for (const std::string& pipe : {std::string("screenshot"), std::string("recording"), std::string("policy")}) {
                bool policy = pipe == "policy";
                std::string text = "{\"schema\":1,\"pipeline\":" + q(pipe) +
                    ",\"ok\":false,\"active\":false,\"active_known\":false,\"supported\":false,\"owned\":false,"
                    "\"matching\":true,\"phase\":\"matching\",\"reason\":\"matching_compatibility\"";
                if (policy) text += std::string(",\"running\":") + (arbitration ? "true" : "false") +
                    ",\"ready\":false,\"policy_ready\":false,\"inventory_known\":false,\"sessions\":0,\"blockers\":0";
                text += '}';
                std::string name = policy ? "policy-status.json" : pipe == "recording" ? "recording-status.json" : "status.json";
                publish(module, name, text, 0, 0, false);
                publish(app, "sf17fix-" + name, text, uid, gid, true);
            }
        };
        heartbeat();
        worker = std::thread([this, heartbeat] {
            std::unique_lock<std::mutex> lock(mutex);
            while (!changed.wait_for(lock, std::chrono::seconds(2), [this] { return finished; })) {
                lock.unlock();
                try { heartbeat(); } catch (...) { /* Main path reports/recovers publication failures. */ }
                lock.lock();
            }
        });
    }
    ~MatchingProgress() { { std::lock_guard<std::mutex> lock(mutex); finished = true; } changed.notify_all(); if (worker.joinable()) worker.join(); }
};
} // namespace

int serve(const std::string& appPath, const std::string& modulePath, const Engine& engine) {
    if (geteuid() != 0) return 20;
    stopping = 0;
    struct sigaction action{}; action.sa_handler = stopSignal; sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, nullptr); sigaction(SIGINT, &action, nullptr);
    std::string app, module;
    std::map<int, Client> clients;
    Policy policy;
    EngineState screenshot, recording;
    bool wantScreenshot = false, wantRecording = false;
    bool publishFailed = false;
    std::string lastError;
    uint64_t nextConnection = 1;
    uint64_t engineChecks = 0, nativeMutations = 0, statusRequests = 0;
    try {
        app = canonicalDirectory(appPath, false); module = canonicalDirectory(modulePath, true);
        if ((!app.starts_with("/data/user_de/") && !app.starts_with("/data/user/")) ||
            !app.ends_with("/net.hawyn.screenshotfreedoma17/files") || !module.starts_with("/data/adb/"))
            throw std::runtime_error("unexpected_broker_directory");
        struct stat appStat{};
        if (stat(app.c_str(), &appStat)) throw std::runtime_error("app_directory_unavailable");
        if (mkdir(kStateDirectory, 0700) && errno != EEXIST) throw std::runtime_error("state_directory_unavailable");
        canonicalDirectory(kStateDirectory, true);
        Fd lock(open((std::string(kStateDirectory) + "/native.lock").c_str(), O_CREAT | O_RDWR | O_NOFOLLOW | O_CLOEXEC, 0600));
        struct stat lockStat{};
        if (lock.fd < 0 || fstat(lock.fd, &lockStat) || !S_ISREG(lockStat.st_mode) || lockStat.st_uid || (lockStat.st_mode & 077) ||
            flock(lock.fd, LOCK_EX | LOCK_NB)) throw std::runtime_error("busy");
        auto runEngine = [&](bool record, bool enable) {
            ++engineChecks;
            auto state = engine(record, enable);
            if (state.reason == "enabled" || state.reason == "disabled") ++nativeMutations;
            return state;
        };
        auto apply = [&](bool forceOriginal) {
            bool configOk = true;
            if (!forceOriginal) {
                try {
                    wantScreenshot = desired(app + "/sf17fix.conf", appStat.st_uid);
                    wantRecording = desired(app + "/sf17fix-recording.conf", appStat.st_uid);
                } catch (const std::exception& error) {
                    lastError = error.what(); policy.fault(); forceOriginal = true; configOk = false;
                }
            }
            bool on = !forceOriginal && !policy.paused();
            screenshot = runEngine(false, on && wantScreenshot);
            if (screenshot.reason == "detach_failed") { stopping = 1; throw FatalTrace("engine_detach_failed_exit"); }
            recording = runEngine(true, on && wantRecording);
            if (recording.reason == "detach_failed") { stopping = 1; throw FatalTrace("engine_detach_failed_exit"); }
            bool ok = screenshot.satisfied(on && wantScreenshot) && recording.satisfied(on && wantRecording);
            if (!ok) {
                policy.fault(); lastError = "engine_state_unverified";
                screenshot = runEngine(false, false);
                if (screenshot.reason == "detach_failed") { stopping = 1; throw FatalTrace("engine_detach_failed_exit"); }
                recording = runEngine(true, false);
                if (recording.reason == "detach_failed") { stopping = 1; throw FatalTrace("engine_detach_failed_exit"); }
            }
            return ok && configOk;
        };
        auto originalVerified = [&] { return screenshot.original() && recording.original(); };
        auto restoredOrUnmanaged = [&] { return screenshot.safeOriginal() && recording.safeOriginal(); };
        auto fields = [&] {
            std::ostringstream out;
            out << ",\"ready\":" << (policy.ready ? "true" : "false") << ",\"policy_ready\":" << (policy.ready ? "true" : "false")
                << ",\"inventory_known\":" << (policy.ready ? "true" : "false")
                << ",\"paused\":" << (policy.paused() ? "true" : "false") << ",\"policy_paused\":" << (policy.paused() ? "true" : "false")
                << ",\"blockers\":" << policy.blockers() << ",\"blocked_count\":" << policy.blockers()
                << ",\"sessions\":" << policy.sessions.size()
                << ",\"original_verified\":" << (originalVerified() ? "true" : "false")
                << ",\"restored_or_unmanaged\":" << (restoredOrUnmanaged() ? "true" : "false")
                << ",\"screenshot_managed\":" << (!screenshot.unmanaged ? "true" : "false")
                << ",\"recording_managed\":" << (!recording.unmanaged ? "true" : "false")
                << ",\"screenshot_original_verified\":" << (screenshot.original() ? "true" : "false")
                << ",\"recording_original_verified\":" << (recording.original() ? "true" : "false")
                << ",\"screenshot_active\":" << (screenshot.active && screenshot.known ? "true" : "false")
                << ",\"recording_active\":" << (recording.active && recording.known ? "true" : "false")
                << ",\"coverage\":" << q(screenshot.unmanaged || recording.unmanaged ? "partial" : "full")
                << ",\"matching\":false,\"phase\":" << q(policy.ready ? "running" : "inventory_wait")
                << ",\"engine_checks\":" << engineChecks << ",\"native_mutations\":" << nativeMutations
                << ",\"status_requests\":" << statusRequests;
            return out.str();
        };
        std::string epoch = "broker-epoch-" + std::to_string(getpid()) + '-' + std::to_string(processStart(getpid()));
        auto reply = [&](bool ok, const std::string& reason, const std::string& session = std::string()) {
            return "{\"schema\":1,\"pipeline\":\"policy\",\"running\":true,\"ok\":" + std::string(ok ? "true" : "false") +
                ",\"reason\":" + q(reason) + ",\"epoch\":" + q(epoch) + ",\"session_id\":" + q(session) + fields() + '}';
        };
        auto statuses = [&] {
            for (bool record : {false, true}) {
                const auto& state = record ? recording : screenshot;
                std::string text = state.json;
                if (text.empty() || text.back() != '}') throw std::runtime_error("engine_status_invalid");
                text.pop_back(); text += fields() + '}';
                publish(module, record ? "recording-status.json" : "status.json", text, 0, 0, false);
                publish(app, record ? "sf17fix-recording-status.json" : "sf17fix-status.json", text, appStat.st_uid, appStat.st_gid, true);
            }
            std::string text = reply(!publishFailed, lastError.empty() ? "observed" : lastError);
            publish(module, "policy-status.json", text, 0, 0, false);
            publish(app, "sf17fix-policy-status.json", text, appStat.st_uid, appStat.st_gid, true);
        };
        { MatchingProgress progress(app, module, appStat.st_uid, appStat.st_gid, true); apply(true); }
        if (!restoredOrUnmanaged()) throw std::runtime_error("startup_restore_unverified");
        Fd server(socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0));
        if (server.fd < 0) throw std::runtime_error("socket_create_failed");
        sockaddr_un address{}; address.sun_family = AF_UNIX;
        size_t nameSize = strlen(kSocket); memcpy(address.sun_path + 1, kSocket, nameSize);
        if (bind(server.fd, reinterpret_cast<sockaddr*>(&address), socklen_t(offsetof(sockaddr_un, sun_path) + nameSize + 1)) ||
            listen(server.fd, 32)) throw std::runtime_error("socket_bind_failed");
        publish(module, "broker.pid", std::to_string(getpid()) + ' ' + std::to_string(processStart(getpid())), 0, 0, false);
        auto nextRefresh = Clock::now();
        while (!stopping) {
            if (Clock::now() >= nextRefresh) {
                try {
                    wantScreenshot = desired(app + "/sf17fix.conf", appStat.st_uid);
                    wantRecording = desired(app + "/sf17fix-recording.conf", appStat.st_uid);
                    apply(false); publishFailed = false; statuses();
                } catch (const FatalTrace&) { throw;
                } catch (const std::exception& error) {
                    lastError = error.what(); policy.fault(); apply(true); publishFailed = true;
                    try { statuses(); } catch (...) {}
                }
                nextRefresh = Clock::now() + std::chrono::seconds(3);
            }
            std::vector<pollfd> polls{{server.fd, POLLIN, 0}};
            for (const auto& [fd, client] : clients) { (void)client; polls.push_back({fd, POLLIN, 0}); }
            auto untilRefresh = std::chrono::duration_cast<std::chrono::milliseconds>(nextRefresh - Clock::now()).count();
            int polled = poll(polls.data(), polls.size(), int(std::clamp<int64_t>(untilRefresh, 0, 1000)));
            if (polled < 0) { if (errno == EINTR) continue; throw std::runtime_error("socket_poll_failed"); }
            if (polls[0].revents & POLLIN) {
                int fd = accept4(server.fd, nullptr, nullptr, SOCK_CLOEXEC | SOCK_NONBLOCK);
                if (fd >= 0) {
                    Client client; socklen_t size = sizeof(client.peer);
                    try {
                        if (clients.size() >= 256 || getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &client.peer, &size) ||
                            size != sizeof(client.peer) || client.peer.pid <= 0) throw std::runtime_error("peer_rejected");
                        if (client.peer.uid && !hostUids(module).count(client.peer.uid)) throw std::runtime_error("peer_rejected");
                        client.start = processStart(client.peer.pid); client.id = nextConnection++;
                        clients.emplace(fd, std::move(client));
                    } catch (...) { close(fd); }
                }
            }
            std::set<int> dropped;
            bool changed = false;
            bool disconnectChangedPolicy = false;
            for (size_t i = 1; i < polls.size(); ++i) {
                if (stopping) break;
                int fd = polls[i].fd; auto found = clients.find(fd); if (found == clients.end()) continue;
                auto& client = found->second;
                if (polls[i].revents & POLLIN) {
                    char bytes[1024]; ssize_t n = recv(fd, bytes, sizeof(bytes), 0);
                    if (n <= 0) { if (n == 0 || (errno != EAGAIN && errno != EINTR)) dropped.insert(fd); }
                    else {
                        if (client.input.empty()) client.partialSince = Clock::now();
                        client.input.append(bytes, size_t(n));
                        for (;;) {
                            if (stopping) break;
                            size_t newline = client.input.find('\n');
                            if (newline == std::string::npos) break;
                            std::string line = client.input.substr(0, newline); client.input.erase(0, newline + 1);
                            if (newline > kMaxLine || std::any_of(line.begin(), line.end(), [](unsigned char c) { return c < 32 || c >= 127; })) {
                                dropped.insert(fd); break;
                            }
                            try {
                                std::istringstream input(line); std::vector<std::string> args; std::string item;
                                while (input >> item) args.push_back(item);
                                if (args.empty()) throw std::runtime_error("usage");
                                bool ok = true; std::string reason, session;
                                if ((args[0] == "STATUS" || args[0] == "PING") && args.size() == 1) { ++statusRequests; reason = "observed"; }
                                else if (args[0] == "READY" && args.size() == 2 && client.peer.uid == 1000 && atom(args[1])) {
                                    policy.setReady(client.id, args[1]); changed = true;
                                    ok = apply(false); reason = ok ? "ready" : "engine_state_unverified";
                                } else if (args[0] == "BEGIN" && args.size() == 5 && client.peer.uid != 0 && atom(args[1]) &&
                                           (args[2] == "SCREENSHOT" || args[2] == "PROJECTION") && (args[3] == "ALLOW" || args[3] == "BLOCK") &&
                                           args[4].size() <= 10 && !args[4].empty() && std::all_of(args[4].begin(), args[4].end(), [](char c) { return c >= '0' && c <= '9'; })) {
                                    uint64_t owner = std::stoull(args[4]);
                                    if (owner > INT32_MAX || (client.peer.uid != 1000 && owner != client.peer.uid) || policy.controller == client.id)
                                        throw std::runtime_error("owner_identity_mismatch");
                                    session = policy.begin(client.id, std::to_string(client.peer.uid) + ':' + std::to_string(client.peer.pid) + ':' + std::to_string(client.start),
                                                           args[1], args[2], args[3] == "ALLOW");
                                    changed = true; ok = apply(false);
                                    if (policy.paused() && !restoredOrUnmanaged()) ok = false;
                                    reason = !ok ? "restore_unverified" : args[3] == "BLOCK" ? "begin_blocked" : policy.paused() ? "begin_paused" : "begin_allowed";
                                } else if (args[0] == "END" && args.size() == 2 && atom(args[1])) {
                                    policy.end(client.id, args[1]); changed = true; ok = apply(false); reason = ok ? "ended" : "engine_state_unverified";
                                } else throw std::runtime_error("usage_or_peer_rejected");
                                if (!sendLine(fd, reply(ok, reason, session))) dropped.insert(fd);
                            } catch (const FatalTrace&) { throw;
                            } catch (const std::exception& error) {
                                if (!sendLine(fd, reply(false, error.what()))) dropped.insert(fd);
                            }
                            if (dropped.count(fd)) break;
                            client.partialSince = Clock::now();
                        }
                    }
                }
                if (client.input.size() > kMaxLine || (!client.input.empty() && Clock::now() - client.partialSince > std::chrono::seconds(2)) ||
                    (polls[i].revents & (POLLERR | POLLHUP | POLLNVAL))) dropped.insert(fd);
            }
            for (int fd : dropped) {
                bool policyChanged = policy.disconnect(clients.at(fd).id);
                disconnectChangedPolicy |= policyChanged; changed |= policyChanged;
                close(fd); clients.erase(fd);
            }
            if (changed) {
                // READY/BEGIN/END already verified the exact state before ACK.
                // A read-only STATUS peer leaving changes no policy or native state.
                if (disconnectChangedPolicy) apply(false);
                try { statuses(); } catch (const std::exception& error) { lastError = error.what(); policy.fault(); apply(true); publishFailed = true; }
            }
        }
        policy.fault(); apply(true);
        try { statuses(); } catch (...) {}
        for (const auto& [fd, client] : clients) { (void)client; close(fd); }
        clients.clear();
        if (restoredOrUnmanaged()) { unlink((module + "/broker.pid").c_str()); return 0; }
        return 20;
    } catch (const std::exception& error) {
        for (const auto& [fd, client] : clients) { (void)client; close(fd); }
        // The lock has unwound here. The supervisor independently restores and
        // verifies both pipelines after any abnormal exit; never auto-enables.
        std::cerr << "policy-serve: " << error.what() << '\n';
        return 20;
    }
}

// List arbitration is disabled here: no policy socket, controller, capture
// identity lookup or lease exists. Keep the verified native cache in one process
// rather than cold-starting two ELF scanners from the shell every second.
int serveDirect(const std::string& appPath, const std::string& modulePath, const Engine& engine) {
    if (geteuid() != 0) return 20;
    stopping = 0;
    struct sigaction action{}; action.sa_handler = stopSignal; sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, nullptr); sigaction(SIGINT, &action, nullptr);
    try {
        std::string app = canonicalDirectory(appPath, false), module = canonicalDirectory(modulePath, true);
        if ((!app.starts_with("/data/user_de/") && !app.starts_with("/data/user/")) ||
            !app.ends_with("/net.hawyn.screenshotfreedoma17/files") || !module.starts_with("/data/adb/"))
            throw std::runtime_error("unexpected_direct_directory");
        struct stat appStat{};
        if (stat(app.c_str(), &appStat)) throw std::runtime_error("app_directory_unavailable");
        if (mkdir(kStateDirectory, 0700) && errno != EEXIST) throw std::runtime_error("state_directory_unavailable");
        canonicalDirectory(kStateDirectory, true);
        Fd lock(open((std::string(kStateDirectory) + "/native.lock").c_str(), O_CREAT | O_RDWR | O_NOFOLLOW | O_CLOEXEC, 0600));
        struct stat lockStat{};
        if (lock.fd < 0 || fstat(lock.fd, &lockStat) || !S_ISREG(lockStat.st_mode) || lockStat.st_uid || (lockStat.st_mode & 077) ||
            flock(lock.fd, LOCK_EX | LOCK_NB)) throw std::runtime_error("busy");
        EngineState screenshot, recording;
        bool wantScreenshot = false, wantRecording = false;
        std::string error;
        uint64_t engineChecks = 0, nativeMutations = 0;
        auto runEngine = [&](bool record, bool enable) {
            ++engineChecks;
            auto state = engine(record, enable);
            if (state.reason == "enabled" || state.reason == "disabled") ++nativeMutations;
            return state;
        };
        auto restore = [&] {
            screenshot = runEngine(false, false);
            if (screenshot.reason == "detach_failed") { stopping = 1; throw FatalTrace("engine_detach_failed_exit"); }
            recording = runEngine(true, false);
            if (recording.reason == "detach_failed") { stopping = 1; throw FatalTrace("engine_detach_failed_exit"); }
            return screenshot.safeOriginal() && recording.safeOriginal();
        };
        auto apply = [&] {
            screenshot = runEngine(false, wantScreenshot);
            if (screenshot.reason == "detach_failed") { stopping = 1; throw FatalTrace("engine_detach_failed_exit"); }
            recording = runEngine(true, wantRecording);
            if (recording.reason == "detach_failed") { stopping = 1; throw FatalTrace("engine_detach_failed_exit"); }
            if (!screenshot.satisfied(wantScreenshot) || !recording.satisfied(wantRecording)) {
                error = "engine_state_unverified"; restore(); return false;
            }
            error.clear(); return true;
        };
        auto statuses = [&] {
            bool ok = error.empty();
            std::string extra = ",\"matching\":false,\"phase\":\"direct\",\"policy_running\":false,"
                "\"policy_ready\":false,\"policy_paused\":false,\"allow_all_tools\":true";
            extra += ",\"engine_checks\":" + std::to_string(engineChecks) + ",\"native_mutations\":" + std::to_string(nativeMutations);
            for (bool record : {false, true}) {
                const auto& state = record ? recording : screenshot;
                std::string text = state.json;
                if (text.empty() || text.back() != '}') throw std::runtime_error("engine_status_invalid");
                text.pop_back(); text += extra + '}';
                publish(module, record ? "recording-status.json" : "status.json", text, 0, 0, false);
                publish(app, record ? "sf17fix-recording-status.json" : "sf17fix-status.json", text, appStat.st_uid, appStat.st_gid, true);
            }
            std::string text = std::string("{\"schema\":1,\"pipeline\":\"policy\",\"running\":false,\"ok\":") +
                (ok ? "true" : "false") + ",\"matching\":false,\"phase\":\"direct\",\"ready\":false,"
                "\"policy_ready\":false,\"paused\":false,\"policy_paused\":false,\"inventory_known\":false,"
                "\"sessions\":0,\"blockers\":0,\"blocked_count\":0,\"allow_all_tools\":true,\"reason\":" +
                q(error.empty() ? "arbitration_disabled" : error) + '}';
            publish(module, "policy-status.json", text, 0, 0, false);
            publish(app, "sf17fix-policy-status.json", text, appStat.st_uid, appStat.st_gid, true);
        };
        { MatchingProgress progress(app, module, appStat.st_uid, appStat.st_gid, false); if (!restore()) throw std::runtime_error("startup_restore_unverified"); }
        publish(module, "broker.pid", std::to_string(getpid()) + ' ' + std::to_string(processStart(getpid())), 0, 0, false);
        Fd notify(inotify_init1(IN_CLOEXEC | IN_NONBLOCK));
        if (notify.fd >= 0 && inotify_add_watch(notify.fd, app.c_str(), IN_CLOSE_WRITE | IN_MOVED_TO | IN_DELETE | IN_ATTRIB | IN_DELETE_SELF | IN_MOVE_SELF) < 0) {
            close(notify.fd); notify.fd = -1;
        }
        auto nextHealth = Clock::now();
        bool configurationChanged = true;
        while (!stopping) {
            bool health = Clock::now() >= nextHealth;
            if (configurationChanged || health || notify.fd < 0) {
                bool beforeScreenshot = wantScreenshot, beforeRecording = wantRecording;
                try {
                    wantScreenshot = desired(app + "/sf17fix.conf", appStat.st_uid);
                    wantRecording = desired(app + "/sf17fix-recording.conf", appStat.st_uid);
                    if (health || configurationChanged || beforeScreenshot != wantScreenshot || beforeRecording != wantRecording) {
                        apply(); statuses(); nextHealth = Clock::now() + std::chrono::seconds(3);
                    }
                } catch (const FatalTrace&) { throw;
                } catch (const std::exception& e) {
                    error = e.what(); restore();
                    try { statuses(); } catch (...) {}
                    nextHealth = Clock::now() + std::chrono::seconds(3);
                }
                configurationChanged = false;
            }
            auto untilHealth = std::chrono::duration_cast<std::chrono::milliseconds>(nextHealth - Clock::now()).count();
            pollfd descriptor{notify.fd, POLLIN, 0};
            int result = poll(&descriptor, notify.fd >= 0 ? 1 : 0, int(std::clamp<int64_t>(untilHealth, 0, 1000)));
            if (result < 0) { if (errno == EINTR) continue; throw std::runtime_error("direct_poll_failed"); }
            if (notify.fd >= 0 && descriptor.revents & POLLIN) {
                alignas(inotify_event) std::array<char, 4096> events{};
                ssize_t count = read(notify.fd, events.data(), events.size());
                for (size_t offset = 0; count > 0 && offset + sizeof(inotify_event) <= size_t(count); ) {
                    auto event = reinterpret_cast<const inotify_event*>(events.data() + offset);
                    if (offset + sizeof(inotify_event) + event->len > size_t(count)) throw std::runtime_error("direct_watch_event_invalid");
                    if (event->mask & (IN_Q_OVERFLOW | IN_DELETE_SELF | IN_MOVE_SELF | IN_IGNORED)) configurationChanged = true;
                    if (event->len && (std::string(event->name) == "sf17fix.conf" || std::string(event->name) == "sf17fix-recording.conf")) configurationChanged = true;
                    offset += sizeof(inotify_event) + event->len;
                }
            }
        }
        bool restored = restore();
        try { statuses(); } catch (...) {}
        if (restored) { unlink((module + "/broker.pid").c_str()); return 0; }
        return 20;
    } catch (const std::exception& e) {
        // As in policy-serve, the supervisor verifies restoration after any
        // abnormal exit; an exception never fabricates successful restoration.
        std::cerr << "direct-serve: " << e.what() << '\n'; return 20;
    }
}
} // namespace sfpolicy
