#ifndef TRANFORCPP_API_H
#define TRANFORCPP_API_H

#include <string>
#include <iostream>
#include <sstream>
#include <thread>
#include <atomic>
#include <mutex>

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

namespace tranforcpp {
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
        std::lock_guard<std::mutex> lock(output_mutex);
        std::cout << msg << std::endl;
    }
}

inline void broadcast(const char* message) {
    std::ostringstream oss;
    oss << R"({"action":"broadcast","message":")" << tranforcpp::escapeJson(message) << R"("})";
    tranforcpp::sendMessage("broadcast", oss.str());
}

inline void sendMessage(const char* player, const char* message) {
    std::ostringstream oss;
    oss << R"({"action":"sendMessage","player":")" << tranforcpp::escapeJson(player)
        << R"(","message":")" << tranforcpp::escapeJson(message) << R"("})";
    tranforcpp::sendMessage("sendMessage", oss.str());
}

inline void sendMsg(const char* player, const char* message) { sendMessage(player, message); }

inline void console(const char* message) {
    std::ostringstream oss;
    oss << R"({"action":"console","message":")" << tranforcpp::escapeJson(message) << R"("})";
    tranforcpp::sendMessage("console", oss.str());
}

inline void dispatchCommand(const char* command, bool sync) {
    std::ostringstream oss;
    oss << R"({"action":"executeCommand","command":")" << tranforcpp::escapeJson(command) << R"(","sync":)" << (sync ? "true" : "false") << R"(})";
    tranforcpp::sendMessage("dispatchCommand", oss.str());
}

#endif
