#!/usr/bin/env python3
"""Apply narrowly checked changes to the pinned upstream runtime."""
from pathlib import Path
import shutil, sys
root = Path(__file__).resolve().parents[1]
target = Path(sys.argv[1]).resolve()

def replace(path, old, new, count=None):
    file = target / path
    text = file.read_text()
    if old not in text:
        raise SystemExit(f'Upstream changed: {path}: {old!r}')
    if count is not None and text.count(old) != count:
        raise SystemExit(f'Unexpected match count: {path}')
    file.write_text(text.replace(old, new))

app = 'app_pojavlauncher'
java = f'{app}/src/main/java/net/kdt/pojavlaunch'
for source in ('ElorinActivity.java', 'ElorinNativeCompat.java', 'RspsElfPatcher.java'):
    shutil.copyfile(root/'overlay'/source, target/java/source)
assets = target/app/'src/main/assets/elorin'
assets.mkdir(parents=True, exist_ok=True)
shutil.copyfile(root/'elorin-bootstrap.lock.json', assets/'bootstrap.json')
replace(f'{app}/src/main/AndroidManifest.xml', 'android:name=".TestStorageActivity"', 'android:name=".ElorinActivity"', 1)
# Keep the JavaGUI activity and native input/display bridge. The upstream
# 720x600 AWT screen clips Elorin's default 765-pixel-wide game window.
replace(f'{java}/AWTCanvasView.java', 'public static final int AWT_CANVAS_WIDTH = 720;', 'public static final int AWT_CANVAS_WIDTH = 1280;', 1)
replace(f'{java}/AWTCanvasView.java', 'public static final int AWT_CANVAS_HEIGHT = 600;', 'public static final int AWT_CANVAS_HEIGHT = 720;', 1)
replace(f'{java}/tasks/AsyncAssetManager.java', '    private AsyncAssetManager(){}', '''    private AsyncAssetManager(){}
    public static void prepareElorin(Context context) throws IOException {
        unpackComponent(context, "caciocavallo17", false);
        unpackComponent(context, "security", true);
        unpackComponent(context, "MioLibPatcher", true);
        Tools.copyAssetFile(context, "resolv.conf", Tools.DIR_DATA, false);
    }''', 1)
# Give this app its own install identity; retain Java namespaces and upstream attribution.
replace(f'{app}/build.gradle', 'org.angelauramc.amethyst', 'org.ether4o4.elorin')
replace(f'{app}/build.gradle', 'Amethyst (Debug)', 'Elorin Experimental')
replace(f'{app}/build.gradle', '        minSdkVersion 21', '        minSdkVersion 26\n        ndk { abiFilters "arm64-v8a" }', 1)
print('Overlay applied to pinned runtime')

# Upstream requests API 37, which is not available on the build runner.
replace(f"{app}/build.gradle", "compileSdk = 37", "compileSdk { version = release(36) { minorApiLevel = 1 } }", 1)

# Match the game's JNA 5.12.1 Java classes (native ABI 6.1.4).
# The upstream AAR 5.14.0 packages ABI 7.0.0 and poisons Native initialization.
replace(f'{app}/build.gradle', 'net.java.dev.jna:jna:5.14.0@aar', 'net.java.dev.jna:jna:5.12.1@aar', 1)
replace(f'{app}/build.gradle', 'versionCode 10000000', 'versionCode 10000003', 1)

# Compile the patcher for Java 8 without requiring a separate legacy JDK toolchain.
replace('MioLibPatcher/build.gradle.kts', '''java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}''', '''java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}''', 1)
