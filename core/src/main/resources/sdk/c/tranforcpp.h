#ifndef TRANFORCPP_C_API_H
#define TRANFORCPP_C_API_H
#include <stdio.h>
#include <stdbool.h>
#include <pthread.h>

extern pthread_mutex_t tranforcpp_output_mutex;
static inline void tranforcpp_json_string(const char *s) {
    const unsigned char *p = (const unsigned char *)(s ? s : "");
    putchar('"');
    for (; *p; ++p) {
        if (*p == '"' || *p == '\\') { putchar('\\'); putchar(*p); }
        else if (*p < 0x20) printf("\\u%04x", (unsigned int)*p);
        else putchar(*p);
    }
    putchar('"');
}
static inline void tranforcpp_emit(const char *action, const char *key, const char *value, const char *player) {
    pthread_mutex_lock(&tranforcpp_output_mutex);
    fputs("{\"action\":", stdout); tranforcpp_json_string(action);
    if (player) { fputs(",\"player\":", stdout); tranforcpp_json_string(player); }
    fputs(",", stdout); tranforcpp_json_string(key); putchar(':'); tranforcpp_json_string(value);
    fputs("}\n", stdout); fflush(stdout);
    pthread_mutex_unlock(&tranforcpp_output_mutex);
}
static inline void broadcast(const char *message) { tranforcpp_emit("broadcast", "message", message, NULL); }
static inline void sendMessage(const char *player, const char *message) { tranforcpp_emit("sendMessage", "message", message, player ? player : ""); }
static inline void sendMsg(const char *player, const char *message) { sendMessage(player, message); }
static inline void console(const char *message) { tranforcpp_emit("console", "message", message, NULL); }
static inline void dispatchCommand(const char *command, bool sync) { (void)sync; tranforcpp_emit("executeCommand", "command", command, NULL); }
static inline void executeCommand(const char *command) { dispatchCommand(command, false); }
#endif
