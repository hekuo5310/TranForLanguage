#include <string.h>
/* No user header or include needed: the compiler injects the SDK. */
int main(void) {
    broadcast("Hello from C!");
    char line[4096];
    /* Events arrive as JSON lines. A production parser must handle long lines. */
    while (fgets(line, sizeof(line), stdin)) {
        if (strstr(line, "\"shutdown\"")) break;
    }
    return 0;
}
