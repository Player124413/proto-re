# proto-re

[Русский](README.ru.md) | English

Native Android port of Prototype (2009). Not an emulator and not Wine: `prototypeenginef.dll` is statically
recompiled from x86 to C, built for arm64 and run on top of my own Win32/D3D9 layer (SDL2, DXVK, FAudio).
The APK has no game data in it, you copy the files from your own copy of the game.

Roadmap (Russian): [ROADMAP.md](ROADMAP.md). Engine notes: [re/subsystems.md](re/subsystems.md).

## Status

Runs on the phone: menus, videos, sound, missions, saves. A full playthrough is not tested yet. Russian text works (fan translation).

- touch controls with a layout editor, camera sensitivity, invert Y, vibration
- gamepad
- Mali uses the system Vulkan driver, Adreno uses Turnip (Mesa) through libadrenotools
- settings in game: render resolution, textures, FPS limit, battery saver, stick dead zone
- ADPF hints so the phone doesn't run the CPU at max all the time, plus a thermal guard that steps the FPS
  limit down (45, then 30) when the device reports thermal pressure and lifts it again as it cools
- first-launch setup screen: checks the copied game files, picks a conservative quality preset for the
  device, backs up the saves (last 5 in `files/backups`) whenever they change

Pixel 6 (Mali-G78): menus 60 fps, gameplay 38-46 at 720p and 33-42 at 1080p. Gameplay is CPU bound right now.
A phone with Adreno 735: about 40 fps on average at 720p.

## TODO

- 60 fps at 1080p. Mostly CPU work: rewrite the hot functions of the main/render threads by hand, cut down
  D3D9 buffer locking, PGO (ThinLTO builds with `LTO=1`; whether it helps needs on-device numbers). Later
  maybe 30 Hz sim with interpolated 60 Hz rendering and FSR/ASR upscaling.
- built-in benchmark so the first-launch quality preset is picked by measurement, not just by RAM size;
  dynamic resolution from thermal headroom instead of only stepping the FPS limit down
- ASTC/ETC2 textures, less heat and battery drain
- controls: context buttons in fights (grab/throw, consume, shield), swipe wheel for powers and disguises,
  target lock on the nearest enemy, separate layouts for helicopter/tank
- test on real Adreno phones and weaker Mali (G57/G68)
- GLES 3.0 fallback for phones with bad Vulkan drivers
- language switch in settings, audio overlay support in case a Russian dub ever shows up

## Installing

Android 10+, arm64, and a bought copy of the game (PC, Steam app 10150, build 19788432).

1. Install `prototype.apk` and open it: the setup screen creates its folder, shows the game folder path
   and checks off the files as they appear - or tap "Choose game folder..." there and pick the PC
   installation folder, the needed files are copied into place automatically.
2. Copy `prototypeenginef.dll`, all `.rcf` and `.p3d` files and the `art`, `audio`, `scripts` folders to
   `/sdcard/Android/data/com.protore.game/files/game/`. For Russian, use the `.rcf` files with the translation applied.
3. Optionally put `protore.ini` in `/sdcard/Android/data/com.protore.game/files/`
   (see [android/protore.ini.example](android/protore.ini.example)). It has to be readable, `chmod 666`.

Every build is signed with the same committed debug keystore (`android/debug.keystore`) and CI stamps
an increasing version code, so a newer APK installs over the old one like a normal update and keeps the
game files and saves. Only when coming from an older CI build (signed with a throwaway per-run key) do
you have to uninstall once.

Logs go to the same folder (`proto-native.log`, `app_process64_d3d9.log`) and to `adb logcat -s protore`.

`protore.ini` keys:

| key | |
|---|---|
| `render_height` | 720, 900, 1080, 0 = screen |
| `texture_skip_mips` | 0 original, 1 half, 2 quarter |
| `fps_limit` | 30 / 45 / 60 |
| `battery_saver` | 30 fps, no big core pinning |
| `perf_hint` | ADPF on/off |
| `vulkan_driver` | `auto` / `system` / `turnip` |
| `present_stats` | DXVK stats in the log |
| `import_profile`, `watchdog` | profiling, hang diagnostics |

Every key also works as an env var `PROTORE_<KEY>`.

## How it works

`tools/recomp/recomp.py` (capstone) turns each engine function into `void f_<va>(Ctx*)` with explicit CPU
state: flags with liveness analysis, x87 and SSE, jump tables, setjmp/longjmp, atomics. All 73,595
real (non-thunk) functions go through with no unsupported instructions; thunks and the heuristic
extra entries from `data_entries.py` are generated too and counted separately in the summary (false
positives among them are dead code).

`src/native` is the runtime: PE loader, the Windows imports the engine uses, D3D9 forwarded to DXVK,
XAudio2 to FAudio, input through SDL2. A few hot paths (skinning, audio) are written by hand.

Some functions are also reconstructed by hand in C++ (re3/reVC style) and checked against the original with
in-process diff tests under Wine (`src/host`, a `binkw32.dll` proxy). About 230 so far, no mismatches.

| path | |
|---|---|
| `src/native/` | runtime: loader, Win32, D3D9 bridge, audio, input, ADPF, Turnip |
| `src/recomp/` | headers for the recompiled code, x86 bridge |
| `src/host/` | Wine proxy: hooks, diff tests, probes |
| `src/core/`, `src/math/` | hand-reconstructed engine code |
| `src/tests/` | diff tests |
| `android/` | Java (activity, touch controls), build scripts, DXVK patches |
| `tools/recomp/` | recompiler, `bisect.sh` |
| `tools/` | RCF/P3D tools, D3D9 bridge generator, misc |
| `tools/ghidra/` | headless Ghidra scripts |
| `re/` | notes, issues, function list |

## Building

Linux. Needs the Android NDK, JDK, SDK build-tools, python3 with `capstone` and `pefile`, cmake, ninja,
meson >= 1.4. Turnip also needs mako, pyyaml, bison, flex, glslangValidator. For the Wine side:
`mingw-w64-gcc` (i686), wine, dxvk. `PROTORE_ENGINE` points to the original DLL
(default `~/Downloads/Prototype (2009)/Prototype/prototypeenginef.dll`).

APK:

```sh
android/build-deps.sh arm64-v8a ~/proto-re-data/android-deps-src ~/proto-re-data/android-deps/arm64-v8a
android/build-turnip.sh ~/proto-re-data/android-deps-src ~/proto-re-data/android-turnip
python3 -I tools/recomp/data_entries.py
python3 -I tools/recomp/recomp.py --functions all --base 0x58000000 --out build/recomp/native --split 400
tools/cool_make.sh -C src/native PLATFORM=android ABI=arm64-v8a
android/build-apk.sh arm64-v8a
android/verify-apk.sh build/apk/prototype.apk arm64-v8a
adb install -r build/apk/prototype.apk
```

The deps (SDL2, FAudio, DXVK, Turnip) are also built by GitHub Actions. `cool_make.sh` is make with
2 jobs that pauses when the CPU gets too hot, my laptop shut down once without it.

GitHub Actions can also package the whole APK (`.github/workflows/android-apk.yml`). On every push it
builds a CI APK against a stub engine table (`tools/recomp/make_stub.py`): deps, Turnip, the runtime,
Java, packaging, signing and `android/verify-apk.sh` all run end to end, but the APK has no game code in
it and is not playable. A real APK is a manual run of the same workflow with `engine=full`, and the run form
asks for a link to your own `prototypeenginef.dll`: upload it to Hugging Face (a repo of yours; the file
page `.../blob/main/...` link works, `android/download-engine.py` rewrites it to the download route) or to
Google Drive (Share → "Anyone with the link"), then paste that link into the `engine_url` field when
starting the run; the optional `engine_sha256` field verifies the download. Careful: run inputs are visible
in the public run history, so anyone could reuse such a link — for a private link leave the field empty and
use the `PROTORE_ENGINE_URL` repository secret instead (plus optional `PROTORE_ENGINE_SHA256`, and an
`HF_TOKEN` read-token secret for a private Hugging Face repo). Either way the DLL and the generated C stay
on the runner and are deleted before the artifact upload — only the APK is kept.

Desktop build (DXVK 2.5.3 with `-Denable_d3d9=true -Ddxvk_native_wsi=sdl2` and FAudio in `~/proto-re-data/deps`):

```sh
tools/cool_make.sh -C src/native
cd ~/proto-re-data/native_run && PROTORE_WINDOWED=1 ~/proto-re/build/native/proto-native ~/proto-re-data/game
```

Wine proxy (game folder = symlinks to the install, original `binkw32.dll` renamed to `binkw32_orig.dll`):

```sh
make -C src/host -j4
cp build/host/binkw32.dll ~/proto-re-data/game/
cd ~/proto-re-data/game && wine prototypef.exe
```

<details>
<summary>debug env vars</summary>

| var | |
|---|---|
| `PROTORE_NOHOOKS=1` | original engine through the proxy |
| `PROTORE_NULLPATCH=1` | write the original bytes back over the hooks |
| `PROTORE_ONLY=a,b` / `PROTORE_SKIP=a,b` | filter hooks by name prefix |
| `PROTORE_DIFFTEST=1` / `=exit` | run diff tests (and exit) |
| `PROTORE_DIFFTEST_RECOMP=1` | recompiled vs original diff tests |
| `PROTORE_RECOMP=1` / `=all` | test set / whole engine recompiled |
| `PROTORE_RECOMP_RANGE=lo-hi` | recompile only this address range |
| `PROTORE_PROBE=openbykey\|watchdir` | file system probes |
| `PROTORE_TRACE_FPU=1` | trace FPU dependent CRT calls |
| `PROTORE_WINDOWED=1` | desktop: windowed |
| `PROTORE_SHOT=300,2000` | dump these frames to `shot_<n>.ppm` |
| `PROTORE_INPUT=2100:Return,...` | press a key at a frame |
| `PROTORE_TRACE_FILES=1` | log file opens |

Finding a function that breaks the game: `MIN_FRAMES=64 RUN_SECONDS=70 [START_LO=N] tools/recomp/bisect.sh`

</details>

## Testing

Thanks to Copyright-1 for testing on Adreno.

## Notes

Russian is ElArchibald's fan translation, xdelta patches for 8 RCF archives (subtitles, text, HUD, fonts).
There's no Russian voice-over for the PC version.

Game files, the original DLL, decompiled code and the generated C are not in the repo and never will be.
No DRM bypass. Bring your own copy of the game, I don't share APKs or game files.

Prototype belongs to Activision / Radical Entertainment.
