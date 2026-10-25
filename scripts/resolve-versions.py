#!/usr/bin/env python3
"""Resolve released targets from official Maven metadata (no guessed loader versions)."""
import argparse
import json
import re
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


def versions(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'TranforCpp-VersionResolver/1.2 (+https://github.com/hekuo5310/TranforCpp)'}), timeout=60) as response:
        return [node.text for node in ET.fromstring(response.read()).findall('./versioning/versions/version')]


def number(version):
    return tuple(map(int, version.split('.')))


def in_range(loader, mc):
    if not re.fullmatch(r'(?:1\.(?:20|21)|26\.[123])(?:\.\d+)?', mc):
        return False
    return (1, 20) <= number(mc) < ((1, 22) if loader == 'forge' else (26, 4)) and (loader != 'neoforge' or number(mc) >= (1, 21))


def resolve():
    rows = []
    fabric = versions('https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml')
    loaders = versions('https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml')
    loader = next(v for v in reversed(loaders) if re.fullmatch(r'\d+\.\d+\.\d+', v))
    apis = {}
    for api in fabric:
        if '+' not in api:
            continue
        mc = api.split('+', 1)[1]
        if in_range('fabric', mc):
            apis[mc] = api
    for mc, api in apis.items():
        rows.append(row('fabric', mc, loader, api))
    forge = versions('https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml')
    builds = {}
    for build in forge:
        mc, _, release = build.partition('-')
        if in_range('forge', mc) and re.fullmatch(r'\d+(?:\.\d+)+', release):
            key = ('forge', mc)
            if key not in builds or number(release) > number(builds[key]):
                builds[key] = release
    neo = versions('https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml')
    for build in neo:
        parts = build.removesuffix('-beta').split('.')
        if not all(p.isdigit() for p in parts):
            continue
        # 21.0 -> 1.21; 21.1 -> 1.21.1; 26.3.0.N -> 26.3.
        if parts[0] == '21':
            mc = '1.21' + ('.' + parts[1] if parts[1] != '0' else '')
        elif parts[0] == '26' and len(parts) == 4:
            mc = '26.' + parts[1] + ('.' + parts[2] if parts[2] != '0' else '')
        else:
            continue
        if in_range('neoforge', mc):
            key = ('neoforge', mc)
            if key not in builds or number(build.removesuffix('-beta')) > number(builds[key].removesuffix('-beta')):
                builds[key] = build
    rows.extend(row(loader, mc, build) for (loader, mc), build in builds.items())
    # Endpoints must exist. Failure is visible instead of silently promising support.
    for loader, endpoints in {'fabric': ['1.20', '26.3'], 'forge': ['1.20', '1.21'], 'neoforge': ['1.21', '26.3']}.items():
        for endpoint in endpoints:
            if not any(r['loader'] == loader and r['minecraft'] == endpoint for r in rows):
                raise ValueError(f'Official metadata missing required endpoint: {loader} {endpoint}')
    return sorted(rows, key=matrix_order)


def matrix_order(row):
    endpoints = [('fabric', '26.3'), ('neoforge', '26.3'), ('forge', '1.21.11'),
                 ('fabric', '1.20'), ('neoforge', '1.21'), ('forge', '1.20'),
                 ('fabric', '1.21.11')]
    pair = (row['loader'], row['minecraft'])
    return (endpoints.index(pair) if pair in endpoints else len(endpoints), row['loader'], number(row['minecraft']))


def row(loader, mc, release, api=''):
    modern = number(mc) >= (26, 1)
    java = '25' if modern else ('21' if number(mc) >= (1, 20, 5) else '17')
    new_loom = modern or (loader == 'fabric' and mc == '1.21.11')
    # Legacy Forge/Loom use Gradle 8; unobfuscated 26.x uses Loom 1.17 + Gradle 9.
    return {'loader': loader, 'minecraft': mc, 'loader_version': release,
            'fabric_api': api, 'java': java, 'gradle': '9.6.0' if new_loom else '8.14.3',
            'loom': '1.17.21' if new_loom else '1.11.8'}


def properties(row):
    return '\n'.join(f'{k}={v}' for k, v in {
        'minecraft_version': row['minecraft'], 'loader_version': row['loader_version'],
        'java_version': row['java'], 'fabric_api_version': row['fabric_api'], 'loom_version': row['loom']}.items()) + '\n'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--matrix', action='store_true')
    parser.add_argument('--spigot', action='store_true')
    parser.add_argument('--loader', choices=['fabric', 'forge', 'neoforge'])
    parser.add_argument('--minecraft')
    parser.add_argument('--output')
    args = parser.parse_args()
    if args.spigot:
        published = versions('https://hub.spigotmc.org/nexus/content/repositories/snapshots/org/spigotmc/spigot-api/maven-metadata.xml')
        rows = sorted({v.removesuffix('-R0.1-SNAPSHOT') for v in published if re.fullmatch(r'(?:1\.\d+(?:\.\d+)?|26\.[123](?:\.\d+)?)-R0\.1-SNAPSHOT', v)}, key=number)
        text = json.dumps({'include': [{'api': v} for v in rows]})
        if args.output: Path(args.output).write_text(text + '\n', encoding='utf-8')
        else: print(text)
        return
    rows = resolve()
    if args.loader or args.minecraft:
        rows = [r for r in rows if (not args.loader or r['loader'] == args.loader) and (not args.minecraft or r['minecraft'] == args.minecraft)]
        if len(rows) != 1:
            parser.error('Specify one released loader/Minecraft pair')
        text = properties(rows[0])
    else:
        text = json.dumps({'include': rows}, separators=(',', ':'))
    if args.output:
        Path(args.output).write_text(text + '\n', encoding='utf-8')
    else:
        print(text)


if __name__ == '__main__':
    main()
