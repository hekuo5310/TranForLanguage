#!/usr/bin/env python3
"""Inspect the actual distributable rather than accepting a successful compile alone."""
import json
import struct
import subprocess
import sys
import tomllib
import zipfile
from pathlib import Path

loader, minecraft, java, pattern = sys.argv[1:]
files = [p for p in Path('.').glob(pattern) if not any(s in p.name for s in ['-sources', '-dev', 'original-'])]
assert len(files) == 1, f'Expected one distributable, got {files}'
with zipfile.ZipFile(files[0]) as jar:
    names = set(jar.namelist())
    assert 'tranforcpp_api.h' in names, 'Missing C++ SDK resource'
    assert 'LICENSE' in names, 'Missing project license'
    assert 'com/github/tranforcpp/core/ProgramRuntime.class' in names
    assert 'com/github/tranforcpp/core/language/LanguageProvider.class' in names
    majors = {struct.unpack('>H', jar.read(name)[6:8])[0] for name in names
              if name.endswith('.class') and name.startswith('com/github/tranforcpp/')}
    assert max(majors) <= int(java) + 44, (java, majors)
    if loader == 'spigot':
        descriptor = jar.read('plugin.yml').decode()
        assert 'main: com.github.tranforcpp.TranforCPlusPlus' in descriptor
        assert 'version: \'1.2.0\'' in descriptor
        assert 'paper-plugin.yml' not in names
        assert not any(name.startswith('com/google/gson/') for name in names)
    else:
        assert 'plugin.yml' not in names
        assert not any(name.startswith('org/bukkit/') for name in names)
        if loader == 'fabric':
            descriptor = json.loads(jar.read('fabric.mod.json'))
            assert descriptor['depends']['minecraft'] == minecraft
            assert descriptor['version'] == '1.2.0'
            assert descriptor['environment'] == 'server'
            assert 'com/github/tranforcpp/fabric/TranforFabric.class' in names
            if not minecraft.startswith('26.'):
                signatures = subprocess.check_output(['javap', '-classpath', str(files[0]), '-p', 'com.github.tranforcpp.fabric.TranforFabric'], text=True)
                assert 'net.minecraft.server.level.ServerPlayer' not in signatures, 'Fabric distributable was not remapped'
        else:
            path = 'META-INF/neoforge.mods.toml' if loader == 'neoforge' else 'META-INF/mods.toml'
            descriptor = tomllib.loads(jar.read(path).decode())
            assert descriptor['mods'][0]['version'] == '1.2.0'
            dependency = next(d for d in descriptor['dependencies']['tranforcpp'] if d['modId'] == 'minecraft')
            assert dependency['versionRange'] == f'[{minecraft}]'
            assert f'com/github/tranforcpp/{loader}/TranforForge.class' in names
print(f'Artifact verified: {files[0]} ({loader}, Java {java}, class versions {sorted(majors)})')
