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
shutil.copyfile(root/'overlay/ElorinActivity.java', target/java/'ElorinActivity.java')
assets = target/app/'src/main/assets/elorin'
assets.mkdir(parents=True, exist_ok=True)
shutil.copyfile(root/'elorin-bootstrap.lock.json', assets/'bootstrap.json')
replace(f'{app}/src/main/AndroidManifest.xml', 'android:name=".TestStorageActivity"', 'android:name=".ElorinActivity"', 1)
# Keep the existing JavaGUI activity and native input/display bridge.
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
