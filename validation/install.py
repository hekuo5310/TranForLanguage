#!/usr/bin/env python3
"""Install official server distributions only inside a marked disposable smoke directory."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('directory', type=Path)
parser.add_argument('--minecraft', required=True)
parser.add_argument('--java', default='java')
args = parser.parse_args()
root = args.directory.resolve()
if not (root / '.tranforlanguage-smoke').is_file():
    parser.error('Use prepare.py to create a disposable directory first')
config = json.loads((root / 'smoke-config.json').read_text())
loader = config['loader']
repo = Path(__file__).resolve().parents[1]
rows = json.loads((repo / 'compatibility/versions.json').read_text())['include']
row = next((r for r in rows if r['loader'] == loader and r['minecraft'] == args.minecraft), None)
if loader != 'spigot' and row is None:
    parser.error('Target not present in the reviewed version lock file')
user_agent = 'TranForLanguage-Validation/2.1.0 (https://github.com/hekuo5310/TranForLanguage)'

def read_json(url):
    request = urllib.request.Request(url, headers={'User-Agent': user_agent})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)

def download(url, destination, sha256=None):
    request = urllib.request.Request(url, headers={'User-Agent': user_agent})
    temporary = destination.with_suffix(destination.suffix + '.download')
    try:
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open('wb') as out:
            while data := response.read(1024 * 1024):
                out.write(data)
    except Exception:
        temporary.unlink(missing_ok=True)
        # Some official Maven endpoints reject urllib's TLS fingerprint.
        subprocess.run(['curl', '-fLsS', '--retry', '2', '--max-time', '300', '-A', user_agent,
                        url, '-o', str(temporary)], check=True)
    if sha256 and hashlib.sha256(temporary.read_bytes()).hexdigest() != sha256:
        temporary.unlink()
        raise RuntimeError('Official SHA-256 mismatch for ' + destination.name)
    temporary.replace(destination)

java = [args.java]
proxy = os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
if proxy:
    parsed = urllib.parse.urlsplit(proxy)
    java += ['-Dhttps.proxyHost=' + str(parsed.hostname), '-Dhttps.proxyPort=' + str(parsed.port or 80)]
# Extra JVM options (such as local trust stores) can be supplied through JAVA_TOOL_OPTIONS.
if loader == 'spigot':
    builds = read_json('https://fill.papermc.io/v3/projects/paper/versions/' + args.minecraft + '/builds')
    build = max((b for b in builds if b['channel'] == 'STABLE'), key=lambda b: b['id'])
    asset = build['downloads']['server:default']
    download(asset['url'], root / 'paper.jar', asset['checksums']['sha256'])
    command = ['-jar', 'paper.jar', 'nogui']
else:
    if loader == 'fabric':
        url = 'https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.0/fabric-installer-1.1.0.jar'
        installer_args = ['server', '-mcversion', args.minecraft, '-loader', row['loader_version'], '-downloadMinecraft', '-dir', str(root)]
    else:
        version = args.minecraft + '-' + row['loader_version'] if loader == 'forge' else row['loader_version']
        group = 'net/minecraftforge/forge' if loader == 'forge' else 'net/neoforged/neoforge'
        host = 'https://maven.minecraftforge.net/' if loader == 'forge' else 'https://maven.neoforged.net/releases/'
        url = host + group + '/' + version + '/' + loader + '-' + version + '-installer.jar'
        installer_args = ['--installServer', str(root)]
    installer = root / 'loader-installer.jar'
    download(url, installer)
    subprocess.run(java + ['-jar', str(installer)] + installer_args, cwd=root, check=True, timeout=1800)
    if loader == 'fabric':
        api = row['fabric_api']
        name = 'fabric-api-' + api + '.jar'
        download('https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/' + api + '/' + name, root / 'mods' / name)
        command = ['-jar', 'fabric-server-launch.jar', 'nogui']
    else:
        group_dir = 'net/minecraftforge/forge' if loader == 'forge' else 'net/neoforged/neoforge'
        launch = root / 'libraries' / group_dir / version / 'unix_args.txt'
        if not launch.is_file():
            raise RuntimeError('Installer did not generate the expected launch arguments')
        command = ['@' + str(launch.relative_to(root)), 'nogui']
(root / 'smoke-command.json').write_text(json.dumps(command) + '\n')
(root / 'smoke-distribution.json').write_text(json.dumps({'loader': loader, 'minecraft': args.minecraft, 'lock': row}, indent=2) + '\n')
print('Installed', loader, args.minecraft, 'in', root)
