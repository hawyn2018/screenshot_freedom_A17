// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <cstdint>
#include <functional>
#include <map>
#include <stdexcept>
#include <string>

namespace sfpolicy {
constexpr const char* kSocket = "hawyn.screenshotcompat.policy.v1";
constexpr size_t kMaxLine = 512;
struct EngineState {
    bool ok = false, supported = false, active = false, known = false, owned = false;
    bool statePresent = false, unmanaged = false;
    std::string reason, json;
    bool original() const { return ok && known && !active && !owned; }
    bool unmanagedOriginal() const { return unmanaged && !supported && !statePresent && !owned && !active && !known; }
    bool safeOriginal() const { return original() || unmanagedOriginal(); }
    bool satisfied(bool enabled) const { return unmanagedOriginal() || (ok && known && active == enabled && (!enabled || owned)); }
};
using Engine = std::function<EngineState(bool recording, bool enable)>;

// Independent of sockets, clocks, Android and patch I/O: directly host-testable.
class Policy {
public:
    struct Session { uint64_t connection; std::string token, identity, kind; bool blocked, orphan; };
    bool ready = false;
    uint64_t controller = 0;
    std::string generation;
    std::map<std::string, Session> sessions;
    size_t blockers() const {
        size_t n = 0; for (const auto& [key, s] : sessions) { (void)key; n += s.blocked; } return n;
    }
    bool paused() const { return !ready || blockers() != 0; }
    void setReady(uint64_t connection, const std::string& value) {
        if (!sessions.empty()) throw std::runtime_error("unresolved_sessions");
        if (controller && controller != connection) throw std::runtime_error("controller_exists");
        controller = connection; generation = value; ready = true;
    }
    std::string begin(uint64_t connection, const std::string& identity, const std::string& token,
                      const std::string& kind, bool allowed) {
        if (controller == connection) throw std::runtime_error("controller_requires_separate_session_connection");
        if (sessions.size() >= 512) throw std::runtime_error("session_limit");
        for (const auto& [key, session] : sessions) {
            if (session.connection == connection) throw std::runtime_error("connection_has_session");
            (void)key;
        }
        std::string key = identity + ":" + token;
        if (sessions.count(key)) throw std::runtime_error("duplicate_token");
        sessions.emplace(key, Session{connection, token, identity, kind, !allowed, false});
        return key;
    }
    void end(uint64_t connection, const std::string& token) {
        for (auto it = sessions.begin(); it != sessions.end(); ++it) {
            if (it->second.connection == connection && it->second.token == token) { sessions.erase(it); return; }
        }
        throw std::runtime_error("session_not_owned");
    }
    bool disconnect(uint64_t connection) {
        bool changed = false;
        if (controller == connection) { ready = false; controller = 0; generation.clear(); changed = true; }
        for (auto& [key, session] : sessions) {
            (void)key;
            if (session.connection == connection) { session.connection = 0; session.blocked = session.orphan = true; changed = true; }
        }
        return changed;
    }
    void fault() { ready = false; }
};

int serve(const std::string& appFiles, const std::string& moduleDir, const Engine& engine);
int serveDirect(const std::string& appFiles, const std::string& moduleDir, const Engine& engine);
} // namespace sfpolicy
