/* Built with the C SDK (as C11) and the C++ SDK (as C++17); keep it valid in both. */
#include "tranforlanguage_rpc.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(__wasi__) && !defined(_REENTRANT)
/* Single-threaded WebAssembly (in-JVM mode): there is no second thread to exclude. */
void tranforlanguage_lock_output(void) {}
void tranforlanguage_unlock_output(void) {}
#else
#include <pthread.h>
static pthread_mutex_t tranforlanguage_output_mutex = PTHREAD_MUTEX_INITIALIZER;
void tranforlanguage_lock_output(void) { pthread_mutex_lock(&tranforlanguage_output_mutex); }
void tranforlanguage_unlock_output(void) { pthread_mutex_unlock(&tranforlanguage_output_mutex); }
#endif

char *tranforlanguage_strdup(const char *value) {
    size_t n = strlen(value ? value : "");
    char *copy = (char *)malloc(n + 1);
    if (!copy) abort();
    memcpy(copy, value ? value : "", n + 1);
    return copy;
}

void tranforlanguage_write_json_string(const char *s) {
    const unsigned char *p = (const unsigned char *)(s ? s : "");
    putchar('"');
    for (; *p; ++p) {
        if (*p == '"' || *p == '\\') { putchar('\\'); putchar(*p); }
        else if (*p == '\n') fputs("\\n", stdout);
        else if (*p < 0x20) printf("\\u%04x", (unsigned int)*p);
        else putchar(*p);
    }
    putchar('"');
}

/* ---- minimal JSON reader for the server's flat event objects ---- */
typedef struct { const char *p; } cursor;
static void ws(cursor *c) { while (*c->p == ' ' || *c->p == '\t' || *c->p == '\r' || *c->p == '\n') c->p++; }
static void put_utf8(char **o, unsigned long cp) {
    if (cp < 0x80) *(*o)++ = (char)cp;
    else if (cp < 0x800) { *(*o)++ = (char)(0xC0 | (cp >> 6)); *(*o)++ = (char)(0x80 | (cp & 0x3F)); }
    else if (cp < 0x10000) { *(*o)++ = (char)(0xE0 | (cp >> 12)); *(*o)++ = (char)(0x80 | ((cp >> 6) & 0x3F)); *(*o)++ = (char)(0x80 | (cp & 0x3F)); }
    else { *(*o)++ = (char)(0xF0 | (cp >> 18)); *(*o)++ = (char)(0x80 | ((cp >> 12) & 0x3F));
           *(*o)++ = (char)(0x80 | ((cp >> 6) & 0x3F)); *(*o)++ = (char)(0x80 | (cp & 0x3F)); }
}
static int hex4(const char *p, unsigned long *v) {
    int i; *v = 0;
    for (i = 0; i < 4; i++) {
        char h = p[i]; *v <<= 4;
        if (h >= '0' && h <= '9') *v |= (unsigned long)(h - '0');
        else if (h >= 'a' && h <= 'f') *v |= (unsigned long)(h - 'a' + 10);
        else if (h >= 'A' && h <= 'F') *v |= (unsigned long)(h - 'A' + 10);
        else return 0;
    }
    return 1;
}
/* Parses a string literal at c->p; returns malloc'd UTF-8 or NULL on syntax error. */
static char *json_string(cursor *c) {
    const char *start; char *out, *o;
    if (*c->p != '"') return NULL;
    start = ++c->p;
    out = o = (char *)malloc(strlen(start) * 2 + 1); /* \uXXXX never grows; raw bytes copy 1:1 */
    if (!out) abort();
    while (*c->p && *c->p != '"') {
        if (*c->p != '\\') { *o++ = *c->p++; continue; }
        c->p++;
        switch (*c->p) {
            case 'n': *o++ = '\n'; break; case 't': *o++ = '\t'; break; case 'r': *o++ = '\r'; break;
            case 'b': *o++ = '\b'; break; case 'f': *o++ = '\f'; break;
            case 'u': {
                unsigned long cp, low;
                if (!hex4(c->p + 1, &cp)) { free(out); return NULL; }
                c->p += 4;
                if (cp >= 0xD800 && cp <= 0xDBFF && c->p[1] == '\\' && c->p[2] == 'u' && hex4(c->p + 3, &low)
                    && low >= 0xDC00 && low <= 0xDFFF) { cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00); c->p += 6; }
                put_utf8(&o, cp); break;
            }
            case '\0': free(out); return NULL;
            default: *o++ = *c->p; /* \" \\ \/ */
        }
        c->p++;
    }
    if (*c->p != '"') { free(out); return NULL; }
    c->p++; *o = '\0';
    return out;
}
static int skip_value(cursor *c);
static int skip_container(cursor *c, char close) {
    c->p++; ws(c);
    if (*c->p == close) { c->p++; return 1; }
    for (;;) {
        if (close == '}') { char *k = json_string(c); if (!k) return 0; free(k); ws(c); if (*c->p++ != ':') return 0; }
        if (!skip_value(c)) return 0;
        ws(c);
        if (*c->p == ',') { c->p++; ws(c); continue; }
        if (*c->p == close) { c->p++; return 1; }
        return 0;
    }
}
static int skip_value(cursor *c) {
    ws(c);
    if (*c->p == '"') { char *s = json_string(c); if (!s) return 0; free(s); return 1; }
    if (*c->p == '{') return skip_container(c, '}');
    if (*c->p == '[') return skip_container(c, ']');
    if (!*c->p) return 0;
    while (*c->p && *c->p != ',' && *c->p != '}' && *c->p != ']') c->p++;
    return 1;
}
static void event_free(tranforlanguage_event *e) {
    int i;
    if (!e) return;
    free(e->event); free(e->id); free(e->from); free(e->method); free(e->topic); free(e->result); free(e->error);
    for (i = 0; i < e->argc; i++) free(e->args[i]);
    free(e->args); free(e);
}
static tranforlanguage_event *event_parse(const char *line) {
    cursor c; tranforlanguage_event *e = (tranforlanguage_event *)calloc(1, sizeof(tranforlanguage_event));
    if (!e) abort();
    c.p = line; ws(&c);
    if (*c.p != '{') goto bad;
    c.p++; ws(&c);
    if (*c.p == '}') goto done;
    for (;;) {
        char *key = json_string(&c), **slot = NULL;
        if (!key) goto bad;
        ws(&c); if (*c.p++ != ':') { free(key); goto bad; }
        ws(&c);
        if (!strcmp(key, "event")) slot = &e->event; else if (!strcmp(key, "id")) slot = &e->id;
        else if (!strcmp(key, "from")) slot = &e->from; else if (!strcmp(key, "method")) slot = &e->method;
        else if (!strcmp(key, "topic")) slot = &e->topic; else if (!strcmp(key, "result")) slot = &e->result;
        else if (!strcmp(key, "error")) slot = &e->error;
        if (slot && *c.p == '"') { free(*slot); if (!(*slot = json_string(&c))) { free(key); goto bad; } }
        else if (!strcmp(key, "ok") && (*c.p == 't' || *c.p == 'f')) { e->ok = *c.p == 't'; skip_value(&c); }
        else if (!strcmp(key, "args") && *c.p == '[') {
            c.p++; ws(&c);
            while (*c.p == '"') {
                char *arg = json_string(&c);
                if (!arg) { free(key); goto bad; }
                e->args = (char **)realloc(e->args, sizeof(char *) * (size_t)(e->argc + 1));
                if (!e->args) abort();
                e->args[e->argc++] = arg; ws(&c);
                if (*c.p == ',') { c.p++; ws(&c); }
            }
            if (*c.p++ != ']') { free(key); goto bad; }
        } else if (!skip_value(&c)) { free(key); goto bad; }
        free(key); ws(&c);
        if (*c.p == ',') { c.p++; ws(&c); continue; }
        if (*c.p == '}') break;
        goto bad;
    }
done:
    if (!e->event) goto bad;
    return e;
bad:
    fprintf(stderr, "tranforlanguage: ignored invalid protocol line\n");
    event_free(e);
    return NULL;
}

/* ---- input pump ---- */
typedef struct node { tranforlanguage_event *event; struct node *next; } node;
typedef struct method_entry { char *name; tranforlanguage_method_fn fn; void *user; struct method_entry *next; } method_entry;
static node *backlog_head, *backlog_tail, *replies;
static method_entry *methods;
static unsigned long next_call_id;

static char *read_line(void) {
    size_t n = 0, cap = 256; int ch;
    char *line = (char *)malloc(cap);
    if (!line) abort();
    while ((ch = getchar()) != EOF && ch != '\n') {
        if (n + 1 >= cap) { cap *= 2; line = (char *)realloc(line, cap); if (!line) abort(); }
        line[n++] = (char)ch;
    }
    if (ch == EOF && n == 0) { free(line); return NULL; }
    line[n] = '\0';
    return line;
}
static tranforlanguage_event *read_event(void) {
    for (;;) {
        char *line = read_line(); tranforlanguage_event *e;
        if (!line) return NULL;
        e = event_parse(line); free(line);
        if (e) return e;
    }
}
static void push(node **head, node **tail, tranforlanguage_event *e) {
    node *n = (node *)malloc(sizeof(node));
    if (!n) abort();
    n->event = e; n->next = NULL;
    if (tail) { if (*tail) (*tail)->next = n; else *head = n; *tail = n; }
    else { n->next = *head; *head = n; }
}
static void write_args(int argc, const char *const *args) {
    int i;
    fputs(",\"args\":[", stdout);
    for (i = 0; i < argc; i++) { if (i) putchar(','); tranforlanguage_write_json_string(args[i]); }
    putchar(']');
}
static void send_reply(const char *id, int ok, const char *text) {
    tranforlanguage_lock_output();
    fputs("{\"action\":\"reply\",\"id\":", stdout); tranforlanguage_write_json_string(id);
    fputs(ok ? ",\"result\":" : ",\"error\":", stdout); tranforlanguage_write_json_string(text);
    fputs("}\n", stdout); fflush(stdout);
    tranforlanguage_unlock_output();
}
static void serve(tranforlanguage_event *e) {
    method_entry *m = methods; char *result = NULL; int ok;
    while (m && strcmp(m->name, e->method ? e->method : "")) m = m->next;
    if (!m) { ok = 0; result = tranforlanguage_strdup("unknown method"); }
    else ok = m->fn(e->argc, e->args, &result, m->user);
    if (e->id) send_reply(e->id, ok, result ? result : "");
    free(result);
}

void tranforlanguage_handle(const char *method, tranforlanguage_method_fn fn, void *user) {
    method_entry *m = (method_entry *)malloc(sizeof(method_entry));
    if (!m) abort();
    m->name = tranforlanguage_strdup(method); m->fn = fn; m->user = user; m->next = methods; methods = m;
}
static void send_call(const char *id, const char *target, const char *method, int argc, const char *const *args) {
    tranforlanguage_lock_output();
    fputs("{\"action\":\"call\",\"target\":", stdout); tranforlanguage_write_json_string(target);
    fputs(",\"method\":", stdout); tranforlanguage_write_json_string(method);
    if (id) { fputs(",\"id\":", stdout); tranforlanguage_write_json_string(id); }
    write_args(argc, args);
    fputs("}\n", stdout); fflush(stdout);
    tranforlanguage_unlock_output();
}
void tranforlanguage_notify(const char *target, const char *method, int argc, const char *const *args) {
    send_call(NULL, target, method, argc, args);
}
void tranforlanguage_publish(const char *topic, int argc, const char *const *args) {
    tranforlanguage_lock_output();
    fputs("{\"action\":\"publish\",\"topic\":", stdout); tranforlanguage_write_json_string(topic);
    write_args(argc, args);
    fputs("}\n", stdout); fflush(stdout);
    tranforlanguage_unlock_output();
}
int tranforlanguage_call(const char *target, const char *method, int argc, const char *const *args, char **out) {
    char id[32];
    snprintf(id, sizeof(id), "%lu", ++next_call_id);
    send_call(id, target, method, argc, args);
    for (;;) {
        node **link = &replies; tranforlanguage_event *e;
        while (*link && strcmp((*link)->event->id ? (*link)->event->id : "", id)) link = &(*link)->next;
        if (*link) {
            node *found = *link; int ok = found->event->ok;
            *link = found->next; e = found->event; free(found);
            if (out) *out = tranforlanguage_strdup(ok ? (e->result ? e->result : "") : (e->error ? e->error : "call failed"));
            event_free(e);
            return ok;
        }
        e = read_event();
        if (!e) { if (out) *out = tranforlanguage_strdup("input closed"); return 0; }
        if (!strcmp(e->event, "reply")) push(&replies, NULL, e);
        else if (!strcmp(e->event, "call")) { serve(e); event_free(e); }
        else {
            int shutdown = !strcmp(e->event, "shutdown");
            push(&backlog_head, &backlog_tail, e);
            if (shutdown) { if (out) *out = tranforlanguage_strdup("server is shutting down"); return 0; }
        }
    }
}
int tranforlanguage_server(const char *method, int argc, const char *const *args, char **out) {
    return tranforlanguage_call("@server", method, argc, args, out);
}
int tranforlanguage_run(tranforlanguage_event_fn handler, void *user) {
    for (;;) {
        tranforlanguage_event *e; int shutdown;
        if (backlog_head) {
            node *n = backlog_head; backlog_head = n->next; if (!backlog_head) backlog_tail = NULL;
            e = n->event; free(n);
        } else if (!(e = read_event())) return 0;
        if (!strcmp(e->event, "call")) { serve(e); event_free(e); continue; }
        if (!strcmp(e->event, "reply")) { event_free(e); continue; } /* late reply after a timeout */
        shutdown = !strcmp(e->event, "shutdown");
        if (handler) handler(e, user);
        event_free(e);
        if (shutdown) return 0;
    }
}
