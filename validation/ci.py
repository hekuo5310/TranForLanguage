#!/usr/bin/env python3
"""Reproduce prepare/install/run with a single checked-in entry point."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--loader', required=True, choices=['spigot', 'fabric', 'forge', 'neoforge'])
parser.add_argument('--minecraft', required=True)
parser.add_argument('--artifact-directory', type=Path, required=True)
parser.add_argument('--directory', type=Path, required=True)
parser.add_argument('--java', default='java')
parser.add_argument('--no-player', action='store_true')
parser.add_argument('--accept-eula', action='store_true')
args = parser.parse_args()
if not args.accept_eula:
    parser.error('Read https://aka.ms/MinecraftEULA and explicitly accept with --accept-eula')
artifacts = list(args.artifact_directory.glob('*.jar'))
if len(artifacts) != 1:
    parser.error('Expected exactly one checked distributable JAR')
validation = Path(__file__).resolve().parent
subprocess.run([sys.executable, str(validation / 'prepare.py'), str(args.directory), '--loader', args.loader,
                '--artifact', str(artifacts[0]), '--accept-eula'], check=True)
subprocess.run([sys.executable, str(validation / 'install.py'), str(args.directory), '--minecraft', args.minecraft,
                '--java', args.java], check=True)
command = json.loads((args.directory / 'smoke-command.json').read_text())
run = ['node', str(validation / 'run.cjs'), str(args.directory)]
if args.no_player:
    run.append('--no-player')
subprocess.run(run + ['--', args.java, '-Xmx2G'] + command, check=True)
