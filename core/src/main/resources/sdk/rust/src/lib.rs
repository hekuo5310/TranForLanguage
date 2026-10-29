//! UTF-8 JSON-lines SDK. The server installs this crate automatically.
use serde::Deserialize;
use serde_json::{json, Value};
use std::io::{self, BufRead, Write};

#[derive(Debug, Deserialize)]
pub struct Event {
    pub event: String,
    pub args: Vec<String>,
}

fn send(message: Value) -> io::Result<()> {
    let stdout = io::stdout();
    let mut output = stdout.lock();
    serde_json::to_writer(&mut output, &message)?;
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

pub fn run(mut handler: impl FnMut(Event)) -> Result<(), Box<dyn std::error::Error>> {
    for line in io::stdin().lock().lines() {
        let event: Event = serde_json::from_str(&line?)?;
        let shutdown = event.event == "shutdown";
        handler(event);
        if shutdown { break; }
    }
    Ok(())
}
