# Elorin Android — experimental compatibility build

**Phone-tested status (build 10000006, November 4, 2026): the native system-ID library now loads on the phone, but window creation failed inside the Substance look-and-feel, so nothing rendered. Build 10000007 contains a candidate fix that is host-verified only.** This project builds an ARM64 Android APK that embeds an Android Java 17 runtime and uses Amethyst/Pojav's AWT display and touch-input bridge. It launches Elorin's actual game entry point without a Minecraft account or Termux.

The APK is a compatibility experiment, not a native rewrite. The first launch downloads checksum-pinned game dependencies directly from Elorin's distribution server. Game binaries are not committed to this repository.

## Download and install

### [Download latest APK](https://nightly.link/ether4o4/Elorin-android/workflows/android.yml/main/Elorin-Android-experimental.zip)

One click downloads the newest successful main-branch build as a ZIP. Extract it, then install `Elorin-Android-experimental.apk` on an ARM64 Android 8+ device. The primary device target is Samsung Galaxy S24 Ultra.

Tap **Prepare and play**. First setup installs the bundled runtime and downloads game libraries.

### Touch controls (10000008+)

| Action | Touch mode (default) | Trackpad mode |
|---|---|---|
| Left click | Tap where you want to click | Tap anywhere (clicks at the pointer) |
| Right click | Long-press (~0.5 s), or arm **Right-tap** and tap | Long-press, or hold **R** |
| Drag (left button held) | Touch and drag | Hold **L** and drag with another finger |
| Move pointer | Pointer follows your finger | Drag one finger |
| Rotate camera | Two-finger drag, or the ▲◀▼▶ buttons | Same |

The top-right bar has **⌨ Keyboard** (opens and closes the on-screen keyboard), **Mode** (Touch/Trackpad, remembered), **Right-tap** (the next tap is a right click), **Esc**, and **☰** (Enter, Copy, Paste, show/hide camera arrows, View log, Force close). Clicks hold the button for 60 ms so the client's game loop registers them. The mouse wheel is not available: the Caciocavallo input bridge ignores scroll events. If startup fails, return to the launcher and tap **Share diagnostic log**. Review the log before sharing; it may contain local paths or game/session details.

## Current limitations

- Elorin's game client uses AWT/Swing extensively and references LWJGL and RuneLite's native AWT library. Platform-specific desktop native artifacts are excluded. The native system-ID and canvas-startup compatibility changes are source-tested but not yet phone-verified; CPU rendering, login, and subsequent native calls remain unverified.
- The display bridge provides desktop-style touch input, not a redesigned mobile game UI.
- No gameplay, login, audio, GPU acceleration, or Android lifecycle acceptance tests have passed yet.
- The bootstrap manifest and runtime are pinned. Updates require refreshing and validating the locks.
- Debug builds are for testing. A production signing key and release process have not been established.

## Reproduce

See `.github/workflows/android.yml`. `runtime.lock.json` pins the upstream source and JRE hash. `scripts/apply_overlay.py` applies the checked changes; `overlay/ElorinActivity.java` contains the Android entry point. Obtain upstream at the exact pinned revision, initialize the indicated submodules, apply the overlay, place the verified JRE at `app_pojavlauncher/src/main/assets/elorin/jre17-arm64.tar.xz`, and run the documented Gradle task.

`bash scripts/test_compat.sh` verifies both compatibility changes against the actual pinned game client. `bash scripts/build_compat_agent.sh runtime` builds the Java 17-compatible agent into the runtime assets.

## Device test of 10000007 — November 4, 2026

The Substance fix worked on the phone. The log shows `Substance title-pane background fix installed` and no `b.101`, and the game renders. Input was the next problem: in the upstream mouse mode there was no practical way to click. 10000008 replaces the upstream Java GUI buttons with the game controls described above (`overlay/ElorinGameActivity.java`, which subclasses the upstream `JavaGUILauncherActivity`). These controls are built in CI, but they have not yet been tested on a phone.

## Device test of 10000006 and candidate fix — November 4, 2026

The phone log isolates the next blocker. It is now one exception, not the rendering layer as a whole:

1. **Fatal, fixed in 10000007 (host-verified, not yet phone-verified):** `Nj` calls `pack()` on the EDT. `Frame.addNotify()` makes Caciocavallo create a frame peer, and `CacioComponentPeer.postInitSwingComponent()` calls `setBackground` on its Swing proxy root pane. Substance's `SubstanceRootPaneUI.propertyChange("background")` then calls `SubstanceCoreUtilities.getTitlePaneComponent(window).setBackground(...)`. That lookup returns `null` when the window has no Substance-decorated title pane, which Substance itself allows, and the unchecked call throws a `NullPointerException`. `pack()` aborts, the client reports `b.101`, the frame never gets a peer, and every subsequent `Canvas.getGraphics()` returns `null`. That causes the repeated `hT.e` drawing errors. The agent now routes only that one `setBackground` call through a helper. The helper applies the color when a title pane exists and does nothing when there is none to color. `bash scripts/test_compat.sh` reproduces the exact device exception with the pinned `radiance-substance-1.0.0.jar`, then shows that the transformed class links under `-Xverify:all` and handles the same event. CI now runs these tests and builds pull requests too.
2. **Native system ID:** `librsps.so` loads from the app-private directory. The linker prints only warnings for unknown GNU version DT entries (ignored by Bionic), and the preserved log has no system-ID failure. Login acceptance is still unverified.
3. **Non-fatal, not changed:** `jr` logs `NumberFormatException: "19-internal"` while parsing a version string. The client catches it and startup continues. `vN`/`vV` plugin start failures occurred while the frame was failing to build, so they may be consequences. Recheck them after this fix.
4. **Audio, not yet addressed:** the runtime has no `libjsound`, so `AudioSystem.getLine` finds no `SourceDataLine`. Expect no sound until an audio bridge is added. This did not block startup.

Next phone check: install 10000007, then confirm that the log contains `Substance title-pane background fix installed` and no `b.101`, and that the loading screen draws.

## Candidate compatibility changes — November 3, 2026

These changes have passed deterministic host-side tests, and the native library has also executed under ARM64 Android 8/Bionic, but **the complete game is not yet device-verified**:

1. **Native system ID:** `RspsElfPatcher` accepts only the exact pinned ARM64 `librsps.so`, changes its dependency from glibc's `libc.so.6` to Android's `libc.so`, neutralizes GNU symbol-version lookups, and converts both absolute machine-ID paths to a working-directory file. It does not alter the system-ID algorithm or its embedded constants. `ElorinNativeCompat` derives the expected 32-hex-character machine ID from Android's real `ANDROID_ID`; it does not generate a random replacement ID. JNA is directed to the patched app-private library.
2. **Canvas startup:** the client starts its game thread from `hF.r()` while the window is still being constructed. The Java agent replaces only that method's thread start with `SwingUtilities.invokeLater`, allowing `Nj` to complete `pack()` and `setVisible(true)` before the game thread creates its Canvas and requests a Graphics peer. `hm.N()` remains synchronous because later window initialization depends on it. No null guard, substitute image surface, or exception suppression is used.
3. **Verification:** the build checks the exact original and patched native hashes, verifies that the agent transforms exactly one pinned `hF.r()` method, and verifies that `QP` remains untransformed with its synchronous `hm.N()` call. A separate ARM64 test loaded the patched library with Android 8's actual Bionic linker and C library, called `getSystemIdWithDiagnosticsV2`, and received a system ID successfully. A successful APK build and native test still do not establish rendering, login, or gameplay compatibility.

## Attribution and licensing

Runtime: [Amethyst Android](https://github.com/AngelAuraMC/Amethyst-Android), based on PojavLauncher; its LGPL-3.0 license and third-party notices remain in the pinned source. Android OpenJDK: [AngelAuraMC builds](https://github.com/AngelAuraMC/angelauramc-openjdk-build); retain the runtime's included OpenJDK legal notices. The modifications in `overlay/` and `scripts/` are provided under LGPL-3.0-or-later, matching the runtime project. Corresponding runtime source is the pinned upstream revision plus this repository's overlay and build instructions. Elorin game code and marks remain their owners' property; no ownership or official affiliation is claimed.


## First device test — October 28, 2026

The Android Java VM executes the game, but the game does not reach a usable login screen:

1. **Confirmed packaging defect, corrected in source:** game JNA 5.12.1 expects native ABI 6.1.4; the upstream Android JNA 5.14.0 AAR supplied ABI 7.0.0. The overlay now pins the AAR to 5.12.1. The actual ARM64 binary contains native version 6.1.4.
2. **Unresolved native compatibility:** `linux-aarch64/librsps.so` inside the pinned game JAR needs `libc.so.6` and GLIBC_2.17 symbols. Its machine-ID code references `/etc/machine-id` and `/var/lib/dbus/machine-id`. This is a Linux glibc build, not an Android Bionic build. The game treats failure to produce a native system ID as fatal before login. Matching JNA alone does not resolve this requirement.
3. **Unresolved rendering:** the loading renderer calls `Canvas.getGraphics()` and passes a null result to `hT.e(Graphics, int, int)`. The repeated drawing exceptions do not identify why the Canvas lacks graphics. No blanket null guard or fabricated image surface has been added: suppressing errors would not make the game visible.
4. **Diagnostics improved:** sharing a large log now preserves the first 40 KB and last 60 KB, instead of dropping all startup errors. Reading is bounded to 100 KB rather than loading an arbitrarily large exception log into memory.

The next required validation is an ARM64 phone run of the candidate build. It must establish whether the derived Android machine ID is accepted by Elorin's login flow and whether the real Caciocavallo screen reaches login and remains usable. If those checks fail, developer-supported Android native libraries or further display integration may still be necessary. A Linux-userland compatibility architecture remains a separate option requiring a different runtime and display integration.

No system IDs are fabricated and the client's login requirement is unchanged. Do not describe a successful APK compilation as a gameplay fix.
