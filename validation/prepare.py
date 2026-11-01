#!/usr/bin/env python3
"""Create disposable, loopback-only server fixtures; never modify an existing server."""
import argparse
import json
from pathlib import Path
import shutil

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('directory', type=Path)
parser.add_argument('--loader', choices=['spigot', 'fabric', 'forge', 'neoforge'], required=True)
parser.add_argument('--artifact', type=Path, required=True)
parser.add_argument('--port', type=int, default=25585)
parser.add_argument('--accept-eula', action='store_true', help='Confirm you accept the Minecraft EULA for this test server')
args = parser.parse_args()
if not args.accept_eula:
    parser.error('Read https://aka.ms/MinecraftEULA and pass --accept-eula if you agree')
if not args.artifact.is_file() or not 1024 <= args.port <= 65535:
    parser.error('Provide an existing artifact and an unprivileged port')
root = args.directory.resolve()
root.mkdir(parents=True, exist_ok=False)
(root / '.tranforlanguage-smoke').write_text(args.loader)
(root / 'eula.txt').write_text('eula=true\n')
(root / 'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={args.port}
online-mode=false
enforce-secure-profile=false
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-players=2
enable-query=false
enable-rcon=false
motd=TranForLanguage disposable validation
''')
mods = root / ('plugins' if args.loader == 'spigot' else 'mods')
mods.mkdir()
shutil.copy2(args.artifact, mods / args.artifact.name)
programs = root / ('plugins/C++ Plugins' if args.loader == 'spigot' else 'config/tranforcpp/programs')
programs.mkdir(parents=True)
common = '''#include <stdio.h>
#include <unistd.h>
#include <string.h>
int main(void) {
    FILE *pid=fopen("pid","w"); fprintf(pid,"%ld",(long)getpid()); fclose(pid);
    FILE *log=fopen("events.jsonl","a");
    console("TFL_READY_ID"); dispatchCommand("say TFL_ACTION_ID", false);
    char line[32768];
    while(fgets(line,sizeof(line),stdin)) {
        fputs(line,log); fflush(log);
        if(strstr(line,"PlayerJoin")) {
            broadcast("TFL_BROADCAST_ID"); sendMessage("TFLProbe","TFL_PRIVATE_ID");
        }
        if(strstr(line,"\\\"shutdown\\\"")) break;
    }
    fclose(log); return 0;
}
'''
# Both native programs deliberately omit the SDK include; the shipped builder injects it.
(programs / 'main.cpp').write_text(common.replace('ID', 'cpp'))
c = programs / 'c'; c.mkdir()
(c / 'main.c').write_text(common.replace('ID', 'c'))
py = programs / 'python'; py.mkdir()
(py / 'main.py').write_text('''import os,sys
from tranforcpp import console,broadcast,send_message,dispatch_command
open('pid','w').write(str(os.getpid()))
console('TFL_READY_python'); dispatch_command('say TFL_ACTION_python')
with open('events.jsonl','a',buffering=1) as log:
    for line in sys.stdin:
        log.write(line)
        if 'PlayerJoin' in line:
            broadcast('TFL_BROADCAST_python'); send_message('TFLProbe','TFL_PRIVATE_python')
        if '"shutdown"' in line: break
''')
rust = programs / 'rust'; rust.mkdir()
(rust / 'main.rs').write_text('''use std::io::{BufRead,Write};
use tranforcpp::{console,broadcast,send_message,dispatch_command};
fn main()->Result<(),Box<dyn std::error::Error>> {
    std::fs::write("pid", std::process::id().to_string())?;
    let mut log=std::fs::OpenOptions::new().create(true).append(true).open("events.jsonl")?;
    console("TFL_READY_rust")?; dispatch_command("say TFL_ACTION_rust")?;
    for line in std::io::stdin().lock().lines() {
        let line=line?; writeln!(log,"{}",line)?; log.flush()?;
        if line.contains("PlayerJoin") {broadcast("TFL_BROADCAST_rust")?;send_message("TFLProbe","TFL_PRIVATE_rust")?;}
        if line.contains("\\\"shutdown\\\"") {break;}
    }
    Ok(())
}
''')
(root / 'smoke-config.json').write_text(json.dumps({'loader': args.loader, 'port': args.port, 'programs': str(programs)}))
print(root)
