#!/usr/bin/env python3
"""Check modified native translation units with the configured Android compile flags."""
from pathlib import Path
import json
import shlex
import subprocess
import sys
root = Path(__file__).resolve().parents[2]
candidates = list((root / 'src/android/app/.cxx/RelWithDebInfo').glob('*/arm64-v8a/compile_commands.json'))
if len(candidates) != 1:
    raise SystemExit('Expected one configured Android compile database')
commands = json.loads(candidates[0].read_text())
for suffix in ['/jni/native.cpp', '/frontend_common/config.cpp', '/frame_gen/lossless_dll.cpp', '/present/frame_gen.cpp', '/present/lsfg_shaders.cpp']:
    entry = next(command for command in commands if command['file'].endswith(suffix))
    args, skip = [], False
    for arg in shlex.split(entry['command']):
        if skip:
            skip = False
            continue
        if arg in ['-o', '-MF', '-MT', '-MQ']:
            skip = True
            continue
        if arg in ['-c', '-MD', '-MMD']:
            continue
        args.append(arg)
    result = subprocess.run(args + ['-fsyntax-only'], cwd=entry['directory'], capture_output=True, text=True)
    print(suffix, result.returncode, flush=True)
    if result.stderr:
        print(result.stderr, flush=True)
    if result.returncode:
        sys.exit(result.returncode)
