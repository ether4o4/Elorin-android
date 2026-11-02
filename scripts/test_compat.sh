#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
build="$root/build/compat-test"
rm -rf "$build"
mkdir -p "$build/classes" "$build/input"

python3 - "$root" "$build/input" <<'PY'
import hashlib, json, pathlib, sys, urllib.request, zipfile
root, output = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
manifest = json.loads((root / 'elorin-bootstrap.lock.json').read_text())
artifact = next(a for a in manifest['artifacts'] if a['name'].startswith('game-client-') and a['name'].endswith('.jar'))
client = output / artifact['name']
if not client.is_file() or hashlib.sha256(client.read_bytes()).hexdigest() != artifact['hash']:
    urllib.request.urlretrieve(artifact['path'], client)
data = client.read_bytes()
assert len(data) == artifact['size'] and hashlib.sha256(data).hexdigest() == artifact['hash']
with zipfile.ZipFile(client) as jar:
    (output / 'librsps.so').write_bytes(jar.read('linux-aarch64/librsps.so'))
print(client)
PY
client="$(find "$build/input" -maxdepth 1 -name 'game-client-*.jar' -print -quit)"

exports=(--add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED)
javac -source 17 -target 17 "${exports[@]}" -d "$build/classes" \
    "$root/overlay/ElorinAndroidAgent.java" \
    "$root/overlay/RspsElfPatcher.java" \
    "$root/tests/ElorinAndroidAgentTest.java" \
    "$root/tests/net/kdt/pojavlaunch/RspsElfPatcherTest.java"
java "${exports[@]}" -cp "$build/classes" ElorinAndroidAgentTest "$client"
java -cp "$build/classes" net.kdt.pojavlaunch.RspsElfPatcherTest "$build/input/librsps.so"
