use tranforcpp::{run, send_message};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    run(|event| {
        if event.event == "PlayerJoin" {
            if let Some(player) = event.args.first() {
                if let Err(error) = send_message(player, "Welcome from Rust!") {
                    eprintln!("Message failed: {error}");
                }
            }
        }
    })
}
