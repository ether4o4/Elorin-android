#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
runtime="${1:?Usage: build_compat_agent.sh <pinned-runtime-directory>}"
build="$root/build/compat-agent"
assets="$runtime/app_pojavlauncher/src/main/assets/elorin"
rm -rf "$build"
mkdir -p "$build/classes" "$assets"

javac -source 17 -target 17 \
    --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
    -d "$build/classes" "$root/overlay/ElorinAndroidAgent.java"
cat > "$build/MANIFEST.MF" <<'EOF'
Manifest-Version: 1.0
Premain-Class: ElorinAndroidAgent
Agent-Class: ElorinAndroidAgent
Can-Redefine-Classes: false
Can-Retransform-Classes: false
EOF
jar cfm "$build/elorin-android-agent.jar" "$build/MANIFEST.MF" -C "$build/classes" .
cp "$build/elorin-android-agent.jar" "$assets/android-agent.jar"
printf 'Built %s\n' "$assets/android-agent.jar"
