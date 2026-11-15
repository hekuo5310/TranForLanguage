//! UTF-8 JSON-lines SDK. The server installs this crate automatically.
//!
//! Programs can call each other: [`handle`] exposes a method, [`call`] waits for its string
//! result, [`publish`] sends a `message` event to other programs. Call [`call`] from the thread
//! running [`run`] (for example inside its handler); the SDK reads stdin on that thread.
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::{HashMap, VecDeque};
use std::fmt;
use std::io::{self, BufRead, Write};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

#[derive(Debug, Clone, Deserialize)]
pub struct Event {
    pub event: String,
    #[serde(default)]
    pub args: Vec<String>,
    /// Sending program for `call`, `reply` and `message` events.
    #[serde(default)]
    pub from: Option<String>,
    /// Published topic of a `message` event.
    #[serde(default)]
    pub topic: Option<String>,
    #[serde(default)]
    id: Option<String>,
    #[serde(default)]
    method: Option<String>,
    #[serde(default)]
    ok: bool,
    #[serde(default)]
    result: Option<String>,
    #[serde(default)]
    error: Option<String>,
}

/// A failed [`call`]: unknown program or method, remote error, timeout or shutdown.
#[derive(Debug, Clone)]
pub struct CallError(pub String);
impl fmt::Display for CallError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result { f.write_str(&self.0) }
}
impl std::error::Error for CallError {}

type Method = Arc<dyn Fn(&[String]) -> Result<String, String> + Send + Sync>;

#[derive(Default)]
struct State {
    backlog: VecDeque<Event>,
    replies: HashMap<String, Event>,
    methods: HashMap<String, Method>,
}

fn state() -> &'static Mutex<State> {
    static STATE: OnceLock<Mutex<State>> = OnceLock::new();
    STATE.get_or_init(Default::default)
}
static IDS: AtomicU64 = AtomicU64::new(0);

fn send(message: Value) -> io::Result<()> {
    let line = serde_json::to_string(&message)?;
    let stdout = io::stdout();
    let mut output = stdout.lock();
    output.write_all(line.as_bytes())?;
    output.write_all(b"\n")?;
    output.flush()
}

pub fn broadcast(message: &str) -> io::Result<()> {
    send(json!({"action": "broadcast", "message": message}))
}
pub fn send_message(player: &str, message: &str) -> io::Result<()> {
    send(json!({"action": "sendMessage", "player": player, "message": message}))
}
pub fn console(message: &str) -> io::Result<()> {
    send(json!({"action": "console", "message": message}))
}
pub fn dispatch_command(command: &str) -> io::Result<()> {
    send(json!({"action": "executeCommand", "command": command}))
}

/// This program's ID as the server knows it (directory name or `@root`).
pub fn program_id() -> String { std::env::var("TRANFORCPP_PROGRAM_ID").unwrap_or_default() }

/// Expose `name` to other programs. `Err` text is returned to the caller as an error.
pub fn handle<F>(name: &str, method: F)
where F: Fn(&[String]) -> Result<String, String> + Send + Sync + 'static {
    state().lock().unwrap().methods.insert(name.to_string(), Arc::new(method));
}

/// Call `method` on program `target` and wait for its string result.
pub fn call(target: &str, method: &str, args: &[&str]) -> Result<String, CallError> {
    let id = (IDS.fetch_add(1, Ordering::Relaxed) + 1).to_string();
    send(json!({"action": "call", "target": target, "method": method, "args": args, "id": id}))
        .map_err(|e| CallError(e.to_string()))?;
    loop {
        if let Some(reply) = state().lock().unwrap().replies.remove(&id) {
            return if reply.ok { Ok(reply.result.unwrap_or_default()) }
                else { Err(CallError(reply.error.unwrap_or_else(|| "call failed".into()))) };
        }
        let event = match read().map_err(|e| CallError(e.to_string()))? {
            Some(event) => event,
            None => return Err(CallError(format!("input closed while waiting for {target}"))),
        };
        match event.event.as_str() {
            "reply" => { let key = event.id.clone().unwrap_or_default(); state().lock().unwrap().replies.insert(key, event); }
            "call" => serve(&event), // answer nested calls (A -> B -> A) instead of deadlocking
            _ => {
                let shutdown = event.event == "shutdown";
                state().lock().unwrap().backlog.push_back(event);
                if shutdown { return Err(CallError("server is shutting down".into())); }
            }
        }
    }
}

/// One-way call; no reply and no waiting.
pub fn notify(target: &str, method: &str, args: &[&str]) -> io::Result<()> {
    send(json!({"action": "call", "target": target, "method": method, "args": args}))
}

/// Send a `message` event with `topic` to every other program that accepts it.
pub fn publish(topic: &str, args: &[&str]) -> io::Result<()> {
    send(json!({"action": "publish", "topic": topic, "args": args}))
}

fn read() -> Result<Option<Event>, Box<dyn std::error::Error>> {
    let mut line = String::new();
    loop {
        line.clear();
        if io::stdin().lock().read_line(&mut line)? == 0 { return Ok(None); }
        if !line.trim().is_empty() { return Ok(Some(serde_json::from_str(&line)?)); }
    }
}

fn serve(event: &Event) {
    let method = state().lock().unwrap().methods.get(event.method.as_deref().unwrap_or("")).cloned();
    let outcome = match method {
        Some(method) => method(&event.args),
        None => Err(format!("unknown method {}", event.method.as_deref().unwrap_or(""))),
    };
    let Some(id) = &event.id else {
        if let Err(error) = outcome { eprintln!("tranforcpp: notification failed: {error}"); }
        return;
    };
    let _ = match outcome {
        Ok(result) => send(json!({"action": "reply", "id": id, "result": result})),
        Err(error) => send(json!({"action": "reply", "id": id, "error": error})),
    };
}

/// Read events until shutdown. Calls are answered with [`handle`] methods; the handler sees
/// game events, `message` events and finally `shutdown`.
pub fn run(mut handler: impl FnMut(Event)) -> Result<(), Box<dyn std::error::Error>> {
    loop {
        let queued = state().lock().unwrap().backlog.pop_front();
        let event = match queued { Some(event) => event, None => match read()? { Some(e) => e, None => break } };
        match event.event.as_str() {
            "call" => { serve(&event); continue; }
            "reply" => continue, // a late reply after a timeout
            _ => {}
        }
        let shutdown = event.event == "shutdown";
        handler(event);
        if shutdown { break; }
    }
    Ok(())
}
