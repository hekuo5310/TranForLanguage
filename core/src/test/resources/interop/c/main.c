#include <stdlib.h>
#include <string.h>
static const char *self;
static char *join(const char *a, const char *sep, const char *b) {
    size_t n = strlen(a) + strlen(sep) + strlen(b) + 1; char *out = (char *)malloc(n);
    snprintf(out, n, "%s%s%s", a, sep, b); return out;
}
static int echo(int argc, char **args, char **result, void *user) {
    (void)user; *result = join(self, ":", argc ? args[0] : ""); return 1;
}
static int bounce(int argc, char **args, char **result, void *user) {
    const char *forward[1];
    (void)user;
    if (argc < 2) { *result = tranforlanguage_strdup("bounce needs target and value"); return 0; }
    forward[0] = args[1];
    return tranforlanguage_call(args[0], "echo", 1, forward, result);
}
static int mine(const tranforlanguage_event *e, const char *name, int argc) {
    return !strcmp(e->event, name) && e->argc >= argc && !strcmp(e->args[0], self);
}
static void on_event(const tranforlanguage_event *e, void *user) {
    char *result = NULL, *line = NULL; const char *me[1]; (void)user; me[0] = self;
    if (mine(e, "Ping", 2)) {
        tranforlanguage_call(e->args[1], "echo", 1, me, &result); line = join(self, "->", result);
    } else if (mine(e, "Nested", 2)) {
        const char *args[2]; char *prefix = join("nested ", self, "");
        args[0] = self; args[1] = self;
        tranforlanguage_call(e->args[1], "bounce", 2, args, &result); line = join(prefix, "=", result); free(prefix);
    } else if (mine(e, "Missing", 1)) {
        char *prefix = join("missing ", self, "");
        tranforlanguage_call("nobody", "echo", 0, NULL, &result); line = join(prefix, ":", result); free(prefix);
    } else if (mine(e, "Announce", 1)) {
        const char *text[1] = {"hi"}; tranforlanguage_publish("greet", 1, text);
    } else if (!strcmp(e->event, "message") && e->topic && !strcmp(e->topic, "greet") && e->argc == 1) {
        char *a = join("greet ", self, " from "), *b = join(a, e->from ? e->from : "", ": ");
        line = join(b, "", e->args[0]); free(a); free(b);
    }
    if (line) broadcast(line);
    free(line); free(result);
}
int main(void) {
    self = getenv("TRANFORLANGUAGE_PROGRAM_ID");
    tranforlanguage_handle("echo", echo, NULL);
    tranforlanguage_handle("bounce", bounce, NULL);
    return tranforlanguage_run(on_event, NULL);
}
