#ifndef TRANFORLANGUAGE_H
#define TRANFORLANGUAGE_H
#include <stdio.h>
#include <stdbool.h>
#include <pthread.h>
#include "tranforlanguage_rpc.h"

extern pthread_mutex_t tranforlanguage_output_mutex;
static inline void tranforlanguage_json_string(const char *s) { tranforlanguage_write_json_string(s); }
static inline void tranforlanguage_emit(const char *action, const char *key, const char *value, const char *player) {
    tranforlanguage_lock_output();
    fputs("{\"action\":", stdout); tranforlanguage_json_string(action);
    if (player) { fputs(",\"player\":", stdout); tranforlanguage_json_string(player); }
    fputs(",", stdout); tranforlanguage_json_string(key); putchar(':'); tranforlanguage_json_string(value);
    fputs("}\n", stdout); fflush(stdout);
    tranforlanguage_unlock_output();
}
static inline void broadcast(const char *message) { tranforlanguage_emit("broadcast", "message", message, NULL); }
static inline void sendMessage(const char *player, const char *message) { tranforlanguage_emit("sendMessage", "message", message, player ? player : ""); }
static inline void sendMsg(const char *player, const char *message) { sendMessage(player, message); }
static inline void console(const char *message) { tranforlanguage_emit("console", "message", message, NULL); }
static inline void dispatchCommand(const char *command, bool sync) { (void)sync; tranforlanguage_emit("executeCommand", "command", command, NULL); }
static inline void executeCommand(const char *command) { dispatchCommand(command, false); }
#endif
