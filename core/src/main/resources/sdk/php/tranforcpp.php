<?php
/**
 * Protocol-v1 SDK for PHP, preloaded by the server. stdout belongs to the protocol; log
 * diagnostics with error_log / STDERR.
 *
 * Programs can call each other: Tranfor::handle exposes a method, Tranfor::call waits for its
 * string result, Tranfor::publish / Tranfor::subscribe broadcast messages.
 */

if (!class_exists('Tranfor', false)) {

class TranforCallError extends RuntimeException {}

final class Tranfor
{
    private static $handlers = [];
    private static $methods = [];
    private static $topics = [];
    private static $backlog = [];
    private static $replies = [];
    private static $nextId = 0;

    /** This program's ID as the server knows it (directory name or "@root"). */
    public static function programId(): string { return (string) getenv('TRANFORCPP_PROGRAM_ID'); }

    private static function send(string $action, array $fields = []): void
    {
        fwrite(STDOUT, json_encode(['action' => $action] + $fields, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE) . "\n");
        fflush(STDOUT);
    }
    private static function strings(array $args): array { return array_values(array_map('strval', $args)); }

    public static function broadcast($message): void { self::send('broadcast', ['message' => (string) $message]); }
    public static function sendMessage($player, $message): void { self::send('sendMessage', ['player' => (string) $player, 'message' => (string) $message]); }
    public static function console($message): void { self::send('console', ['message' => (string) $message]); }
    public static function dispatchCommand($command): void { self::send('executeCommand', ['command' => (string) $command]); }

    /** Tranfor::on('PlayerJoin', function ($player) { ... }); */
    public static function on(string $event, callable $handler): void { self::$handlers[$event][] = $handler; }
    /** Expose a method; its return value is sent back as a string. */
    public static function handle(string $method, callable $handler): void { self::$methods[$method] = $handler; }
    /** Receive Tranfor::publish($topic, ...) from other programs: $handler($sender, ...$args). */
    public static function subscribe(string $topic, callable $handler): void { self::$topics[$topic][] = $handler; }

    public static function call(string $target, string $method, ...$args): string
    {
        $id = (string) ++self::$nextId;
        self::send('call', ['target' => $target, 'method' => $method, 'args' => self::strings($args), 'id' => $id]);
        while (!array_key_exists($id, self::$replies)) {
            $message = self::read();
            if ($message === null) throw new TranforCallError("input closed while waiting for $target");
            switch ($message['event']) {
                case 'reply': self::$replies[(string) $message['id']] = $message; break;
                case 'call': self::serve($message); break; // answer nested calls instead of deadlocking
                default:
                    self::$backlog[] = $message;
                    if ($message['event'] === 'shutdown') throw new TranforCallError('server is shutting down');
            }
        }
        $reply = self::$replies[$id];
        unset(self::$replies[$id]);
        if (empty($reply['ok'])) throw new TranforCallError($reply['error'] ?? 'call failed');
        return (string) ($reply['result'] ?? '');
    }

    public static function notify(string $target, string $method, ...$args): void
    {
        self::send('call', ['target' => $target, 'method' => $method, 'args' => self::strings($args)]);
    }

    public static function publish(string $topic, ...$args): void
    {
        self::send('publish', ['topic' => $topic, 'args' => self::strings($args)]);
    }

    private static function read(): ?array
    {
        while (($line = fgets(STDIN)) !== false) {
            if (trim($line) === '') continue;
            $message = json_decode($line, true);
            if (is_array($message)) return $message;
            fwrite(STDERR, "tranforcpp: invalid protocol line\n");
        }
        return null;
    }

    private static function serve(array $message): void
    {
        $handler = self::$methods[$message['method'] ?? ''] ?? null;
        try {
            if ($handler === null) throw new TranforCallError('unknown method ' . ($message['method'] ?? ''));
            $result = $handler(...($message['args'] ?? []));
            if (isset($message['id'])) self::send('reply', ['id' => $message['id'], 'result' => (string) $result]);
        } catch (Throwable $e) {
            $text = get_class($e) . ': ' . $e->getMessage();
            if (isset($message['id'])) self::send('reply', ['id' => $message['id'], 'error' => $text]);
            else fwrite(STDERR, "tranforcpp: notification failed: $text\n");
        }
    }

    public static function run(): void
    {
        for (;;) {
            $event = self::$backlog ? array_shift(self::$backlog) : self::read();
            if ($event === null) return;
            $kind = $event['event'];
            if ($kind === 'call') { self::serve($event); continue; }
            if ($kind === 'reply') continue; // a late reply after a timeout
            $args = $event['args'] ?? [];
            if ($kind === 'message') foreach (self::$topics[$event['topic'] ?? ''] ?? [] as $h) $h($event['from'] ?? '', ...$args);
            foreach (self::$handlers[$kind] ?? [] as $h) $h(...$args);
            if ($kind === 'shutdown') return;
        }
    }
}

}
