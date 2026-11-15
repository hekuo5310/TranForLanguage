use tranforlanguage::{broadcast, call, handle, program_id, publish, run};

fn main() {
    let me = program_id();
    let echo_id = me.clone();
    handle("echo", move |args| Ok(format!("{}:{}", echo_id, args.first().cloned().unwrap_or_default())));
    handle("bounce", |args| call(&args[0], "echo", &[&args[1]]).map_err(|e| e.to_string()));
    run(|e| {
        let mine = e.args.first() == Some(&me);
        match e.event.as_str() {
            "Ping" if mine => { broadcast(&format!("{}->{}", me, call(&e.args[1], "echo", &[&me]).unwrap())).unwrap(); }
            "Nested" if mine => { broadcast(&format!("nested {}={}", me, call(&e.args[1], "bounce", &[&me, &me]).unwrap())).unwrap(); }
            "Missing" if mine => { broadcast(&format!("missing {}:{}", me, call("nobody", "echo", &[]).unwrap_err())).unwrap(); }
            "Announce" if mine => { publish("greet", &["hi"]).unwrap(); }
            "message" if e.topic.as_deref() == Some("greet") => {
                broadcast(&format!("greet {} from {}: {}", me, e.from.clone().unwrap_or_default(), e.args[0])).unwrap();
            }
            _ => {}
        }
    }).unwrap();
}
