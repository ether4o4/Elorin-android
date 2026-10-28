# Elorin Android — experimental compatibility build

**Not yet verified playable.** This project builds an ARM64 Android APK that embeds an Android Java 17 runtime and uses Amethyst/Pojav's AWT display and touch-input bridge. It launches Elorin's actual game entry point without a Minecraft account or Termux.

The APK is a compatibility experiment, not a native rewrite. The first launch downloads checksum-pinned game dependencies directly from Elorin's distribution server. Game binaries are not committed to this repository.

## Build and install

The **Experimental Android APK** GitHub Actions workflow builds a debug-signed APK on pushes to main. Download its `Elorin-Android-experimental` artifact after a successful run, extract it, and install the APK on an ARM64 Android 8+ device. The primary device target is Samsung Galaxy S24 Ultra.

Tap **Prepare and play**. First setup installs the bundled runtime and downloads game libraries. The Java display offers a keyboard, mouse mode, and left/right mouse buttons. If startup fails, return to the launcher and tap **Share diagnostic log**. Review the log before sharing; it may contain local paths or game/session details.

## Current limitations

- Elorin's game client uses AWT/Swing extensively and references LWJGL and RuneLite's native AWT library. Desktop native binaries are intentionally excluded because Android cannot load them. These calls may still prevent startup; CPU rendering compatibility is unverified.
- The display bridge provides desktop-style touch input, not a redesigned mobile game UI.
- No gameplay, login, audio, GPU acceleration, or Android lifecycle acceptance tests have passed yet.
- The bootstrap manifest and runtime are pinned. Updates require refreshing and validating the locks.
- Debug builds are for testing. A production signing key and release process have not been established.

## Reproduce

See `.github/workflows/android.yml`. `runtime.lock.json` pins the upstream source and JRE hash. `scripts/apply_overlay.py` applies the checked changes; `overlay/ElorinActivity.java` contains the Android entry point. Obtain upstream at the exact pinned revision, initialize the indicated submodules, apply the overlay, place the verified JRE at `app_pojavlauncher/src/main/assets/elorin/jre17-arm64.tar.xz`, and run the documented Gradle task.

## Attribution and licensing

Runtime: [Amethyst Android](https://github.com/AngelAuraMC/Amethyst-Android), based on PojavLauncher; its LGPL-3.0 license and third-party notices remain in the pinned source. Android OpenJDK: [AngelAuraMC builds](https://github.com/AngelAuraMC/angelauramc-openjdk-build); retain the runtime's included OpenJDK legal notices. The modifications in `overlay/` and `scripts/` are provided under LGPL-3.0-or-later, matching the runtime project. Corresponding runtime source is the pinned upstream revision plus this repository's overlay and build instructions. Elorin game code and marks remain their owners' property; no ownership or official affiliation is claimed.
