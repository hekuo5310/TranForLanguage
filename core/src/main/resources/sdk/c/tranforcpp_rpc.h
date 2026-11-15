#ifndef TRANFORCPP_RPC_H
#define TRANFORCPP_RPC_H
/* Event loop and inter-program calls shared by the C and C++ SDKs. */
#ifdef __cplusplus
extern "C" {
#endif

typedef struct tranforcpp_event {
    char *event;   /* "PlayerJoin", "call", "reply", "message", "shutdown", ... */
    char *id;      /* call/reply correlation id, or NULL */
    char *from;    /* sending program ID for call/reply/message */
    char *method;  /* call: method name */
    char *topic;   /* message: published topic */
    char *result;  /* reply: result text when ok */
    char *error;   /* reply: error text when not ok */
    int ok;
    int argc;
    char **args;
} tranforcpp_event;

/* Return non-zero for success. Set *result to a malloc'd string (result or error text) or leave NULL. */
typedef int (*tranforcpp_method_fn)(int argc, char **args, char **result, void *user);
typedef void (*tranforcpp_event_fn)(const tranforcpp_event *event, void *user);

void tranforcpp_lock_output(void);
void tranforcpp_unlock_output(void);
/* Writes a JSON string literal to stdout; call with the output lock held. */
void tranforcpp_write_json_string(const char *value);
char *tranforcpp_strdup(const char *value);

/* Expose a method other programs can call. Register before tranforcpp_run. */
void tranforcpp_handle(const char *method, tranforcpp_method_fn fn, void *user);
/* Call method on another program and wait. Returns 1 on success, 0 on error.
   *out receives a malloc'd result or error message; release it with free(). */
int tranforcpp_call(const char *target, const char *method, int argc, const char *const *args, char **out);
/* One-way call without a reply. */
void tranforcpp_notify(const char *target, const char *method, int argc, const char *const *args);
/* Send a message event to every other program that accepts "message". */
void tranforcpp_publish(const char *topic, int argc, const char *const *args);
/* Read events until shutdown or end of input. Calls are answered automatically.
   The handler sees game events, "message" events and finally "shutdown". */
int tranforcpp_run(tranforcpp_event_fn handler, void *user);

#ifdef __cplusplus
}
#endif
#endif
