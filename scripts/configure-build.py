#!/usr/bin/env python3
"""Write loader properties from the reviewed lock file, without network access."""
import importlib.util
import json
import sys
from pathlib import Path
spec = importlib.util.spec_from_file_location('versions', Path(__file__).with_name('resolve-versions.py'))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
root = Path(__file__).resolve().parent.parent
loader, minecraft = sys.argv[1:]
rows = json.loads((root / 'compatibility/versions.json').read_text())['include']
row = next(r for r in rows if r['loader'] == loader and r['minecraft'] == minecraft)
(root / 'adapters' / loader / 'gradle.properties').write_text(v.properties(row), encoding='utf-8')
print(f"{loader} {minecraft}: Gradle {row['gradle']}, game Java {row['java']}")
