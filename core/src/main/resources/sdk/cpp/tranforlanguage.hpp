#ifndef TRANFORLANGUAGE_HPP
#define TRANFORLANGUAGE_HPP

#include <string>
#include <iostream>
#include <sstream>
#include <thread>
#include <atomic>
#include <mutex>
#include <functional>
#include <map>
#include <stdexcept>
#include <cstdlib>
#include <vector>
#include "tranforlanguage_rpc.h"

extern "C" {

void onPlayerJoin(const char* playerName);
void onPlayerQuit(const char* playerName);
void onPlayerChat(const char* playerName, const char* message);
void onShutdown();
void onBlockBreak(const char* playerName, const char* blockType);
void onBlockPlace(const char* playerName, const char* blockType);
void onBlockIgnite(const char* playerName, const char* blockType);
void onEntityDamage(const char* entityName, const char* damage);
void onEntityDeath(const char* entityName);
void onEntitySpawn(const char* entityType);
void onPlayerDeath(const char* playerName, const char* deathMessage);
void onPlayerMove(const char* playerName);
void onPlayerRespawn(const char* playerName);
void onPlayerInteract(const char* playerName, const char* action, const char* itemType);
void onPlayerDropItem(const char* playerName, const char* itemType);
void onPlayerPickupItem(const char* playerName, const char* itemType);
void onInventoryClick(const char* playerName, const char* slot, const char* itemType);
void onInventoryOpen(const char* playerName);
void onInventoryClose(const char* playerName);
void onServerCommand(const char* sender, const char* command);
void onWorldLoad(const char* worldName);
void onWeatherChange(const char* worldName, const char* weatherState);
void onHangingBreak(const char* entityType, const char* cause);

}

inline void broadcast(const char* message);
void sendMsg(const char* player, const char* message);
inline void console(const char* message);
inline void dispatchCommand(const char* command, bool sync = false);

namespace tranforlanguage {
    inline std::atomic<bool> running(true);
    inline std::mutex output_mutex;
    inline std::string escapeJson(const char* value) {
        std::ostringstream out;
        for (const unsigned char c : std::string(value ? value : "")) {
            switch (c) {
                case '"': out << "\\\""; break;
                case '\\': out << "\\\\"; break;
                case '\n': out << "\\n"; break;
                case '\r': out << "\\r"; break;
                case '\t': out << "\\t"; break;
                default:
                    if (c < 0x20) {
                        const char* hex = "0123456789abcdef";
                        out << "\\u00" << hex[c >> 4] << hex[c & 15];
                    } else out << c;
            }
        }
        return out.str();
    }

    inline void sendMessage(const std::string& action, const std::string& msg) {
        (void)action;
        // Shares the SDK output lock with calls and replies so lines never interleave.
        tranforlanguage_lock_output();
        std::cout << msg << std::endl;
        tranforlanguage_unlock_output();
    }
}

inline void broadcast(const char* message) {
    std::ostringstream oss;
    oss << R"({"action":"broadcast","message":")" << tranforlanguage::escapeJson(message) << R"("})";
    tranforlanguage::sendMessage("broadcast", oss.str());
}

inline void sendMessage(const char* player, const char* message) {
    std::ostringstream oss;
    oss << R"({"action":"sendMessage","player":")" << tranforlanguage::escapeJson(player)
        << R"(","message":")" << tranforlanguage::escapeJson(message) << R"("})";
    tranforlanguage::sendMessage("sendMessage", oss.str());
}

inline void sendMsg(const char* player, const char* message) { sendMessage(player, message); }

inline void console(const char* message) {
    std::ostringstream oss;
    oss << R"({"action":"console","message":")" << tranforlanguage::escapeJson(message) << R"("})";
    tranforlanguage::sendMessage("console", oss.str());
}

inline void dispatchCommand(const char* command, bool sync) {
    std::ostringstream oss;
    oss << R"({"action":"executeCommand","command":")" << tranforlanguage::escapeJson(command) << R"(","sync":)" << (sync ? "true" : "false") << R"(})";
    tranforlanguage::sendMessage("dispatchCommand", oss.str());
}

namespace tranforlanguage {
    /** Thrown by call() when the target is missing, fails or times out. */
    struct CallError : std::runtime_error { using std::runtime_error::runtime_error; };
    using Args = std::vector<std::string>;
    using Method = std::function<std::string(const Args&)>;
    using EventHandler = std::function<void(const tranforlanguage_event&)>;

    inline std::vector<const char*> c_args(const Args& args) {
        std::vector<const char*> out;
        for (const std::string& arg : args) out.push_back(arg.c_str());
        return out;
    }
    /** Calls method on another program and waits for its string result. */
    inline std::string call(const std::string& target, const std::string& method, const Args& args = {}) {
        std::vector<const char*> argv = c_args(args);
        char* out = nullptr;
        int ok = tranforlanguage_call(target.c_str(), method.c_str(), (int)argv.size(), argv.data(), &out);
        std::string text = out ? out : "";
        std::free(out);
        if (!ok) throw CallError(text);
        return text;
    }
    /** Calls a game action on the server, e.g. server("teleport", {"Alex", "0", "64", "0"}). */
    inline std::string server(const std::string& method, const Args& args = {}) { return call("@server", method, args); }
    inline void notify(const std::string& target, const std::string& method, const Args& args = {}) {
        std::vector<const char*> argv = c_args(args);
        tranforlanguage_notify(target.c_str(), method.c_str(), (int)argv.size(), argv.data());
    }
    inline void publish(const std::string& topic, const Args& args = {}) {
        std::vector<const char*> argv = c_args(args);
        tranforlanguage_publish(topic.c_str(), (int)argv.size(), argv.data());
    }
    /** Exposes a method; exceptions become error replies. Register before run(). */
    inline void handle(const std::string& name, Method method) {
        Method* stored = new Method(std::move(method)); // lives for the whole process
        tranforlanguage_handle(name.c_str(), [](int argc, char** argv, char** result, void* user) -> int {
            try {
                *result = tranforlanguage_strdup((*static_cast<Method*>(user))(Args(argv, argv + argc)).c_str());
                return 1;
            } catch (const std::exception& e) { *result = tranforlanguage_strdup(e.what()); }
            catch (...) { *result = tranforlanguage_strdup("unknown C++ exception"); }
            return 0;
        }, stored);
    }
    /** Reads events until shutdown, answering calls; the handler also sees "message" and "shutdown". */
    inline int run(EventHandler handler) {
        return tranforlanguage_run([](const tranforlanguage_event* event, void* user) {
            (*static_cast<EventHandler*>(user))(*event);
        }, &handler);
    }
}

#endif
