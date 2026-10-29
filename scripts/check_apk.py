#!/usr/bin/env python3
"""Reject the JNA/native mismatch observed in the first phone test."""
import json, pathlib, sys, zipfile
root = pathlib.Path(__file__).resolve().parents[1]
manifest = json.loads((root / 'elorin-bootstrap.lock.json').read_text())
assert any(a['name'] == 'jna-5.12.1.jar' for a in manifest['artifacts'])
with zipfile.ZipFile(sys.argv[1]) as apk:
    native = apk.read('lib/arm64-v8a/libjnidispatch.so')
    assert b'6.1.4\0' in native, 'Game JNA requires native ABI 6.1.4'
    assert b'7.0.0\0' not in native, 'Incompatible JNA ABI 7.0.0 found'
    assert 'assets/elorin/jre17-arm64.tar.xz' in apk.namelist()
print('PASS: APK contains the native JNA ABI required by the game')
