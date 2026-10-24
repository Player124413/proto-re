#!/bin/bash
# Static checks of a packaged APK: signature, alignment, manifest badging, expected contents, ELF
# architectures, libmain.so dependencies and the Java entry points. Catches packaging regressions without a
# phone. Used by .github/workflows/android-apk.yml, useful locally too.
#
# Usage: android/verify-apk.sh <apk> [abi]          (default abi: arm64-v8a)
# Env:   EXPECT_TURNIP=0|1 (default 1: libvulkan_freedreno.so must be bundled)
#        ANDROID_HOME / BUILD_TOOLS locate aapt2, apksigner and zipalign.
set -euo pipefail

APK=${1:?usage: android/verify-apk.sh <apk> [abi]}
ABI=${2:-arm64-v8a}
EXPECT_TURNIP=${EXPECT_TURNIP:-1}

fail() {
    echo "verify-apk: FAIL: $*" >&2
    # In GitHub Actions, surface the reason as an annotation too (visible in the run header/API, not
    # only buried in the log).
    [ "${GITHUB_ACTIONS:-}" = true ] && echo "::error title=verify-apk::FAIL: $*"
    exit 1
}
ok() { echo "verify-apk: ok: $*"; }

[ -f "$APK" ] || fail "no such file: $APK"
command -v unzip >/dev/null || fail "unzip not found in PATH"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
if [ -z "${BUILD_TOOLS:-}" ]; then
    BUILD_TOOLS=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)
    BUILD_TOOLS=${BUILD_TOOLS%/}
fi
[ -n "$BUILD_TOOLS" ] && [ -x "$BUILD_TOOLS/aapt2" ] ||
    fail "aapt2 not found (looked under $SDK/build-tools; set ANDROID_HOME or BUILD_TOOLS)"
for t in apksigner zipalign; do
    [ -x "$BUILD_TOOLS/$t" ] || fail "$t not found in $BUILD_TOOLS"
done

# 1. Signature and 16-byte page alignment (zipalign -P 16 in build-apk.sh).
"$BUILD_TOOLS/apksigner" verify "$APK" || fail "apksigner verify failed"
ok "signature"
"$BUILD_TOOLS/zipalign" -c -P 16 4 "$APK" || fail "zipalign check (-P 16) failed"
ok "zipalign"

# 2. Manifest as packaged. Badging formats differ between aapt2 versions (quotes/spacing, sdkVersion vs
# minSdkVersion), so values are parsed rather than matched literally.
BADGING=$("$BUILD_TOOLS/aapt2" dump badging "$APK")
badge_line() { grep -E "^$1" <<<"$BADGING" | head -n 1 || true; }
badge_num() { badge_line "$1" | grep -oE '[0-9]+' | head -n 1 || true; }
grep -q "^package: name='com.protore.game'" <<<"$BADGING" || fail "unexpected package name in badging"
min_sdk=$(badge_num 'sdkVersion')
[ -n "$min_sdk" ] || min_sdk=$(badge_num 'minSdkVersion')
[ "$min_sdk" = 26 ] ||
    fail "unexpected minSdkVersion '$min_sdk' (want 26); badging starts: $(head -n 3 <<<"$BADGING" | tr '\n' ' ')"
target_sdk=$(badge_num 'targetSdkVersion')
[ "$target_sdk" = 35 ] ||
    fail "unexpected targetSdkVersion '$target_sdk' (want 35); badging starts: $(head -n 3 <<<"$BADGING" | tr '\n' ' ')"
badge_line 'native-code:' | grep -q "'$ABI'" || fail "native-code does not include $ABI: $(badge_line 'native-code:')"
badge_line 'launchable-activity:' | grep -q 'com.protore.game.SetupActivity' ||
    fail "SetupActivity (the first-launch screen) is not the launchable activity: $(badge_line 'launchable-activity:')"
ok "manifest badging (minSdk $min_sdk, targetSdk $target_sdk)"

# 3. Contents: everything the game needs, and never any game data.
LIST=$(unzip -Z1 "$APK")
require() { grep -qx "$1" <<<"$LIST" || fail "missing entry in APK: $1"; }
require "AndroidManifest.xml"
require "classes.dex"
require "resources.arsc"
for lib in libmain.so libSDL2.so libFAudio.so libdxvk_d3d9.so; do
    require "lib/$ABI/$lib"
done
if [ "$EXPECT_TURNIP" = 1 ]; then
    require "lib/$ABI/libvulkan_freedreno.so"
elif grep -qx "lib/$ABI/libvulkan_freedreno.so" <<<"$LIST"; then
    echo "verify-apk: note: Turnip bundled although EXPECT_TURNIP=0"
fi
if grep -qiE '\.(dll|exe|rcf|p3d|bik|gfx|dds)$' <<<"$LIST"; then
    grep -iE '\.(dll|exe|rcf|p3d|bik|gfx|dds)$' <<<"$LIST" >&2
    fail "game files must never be packaged into the APK (listed above)"
fi
ok "contents"

# 4. ELF architecture of every bundled library, libmain.so dependencies, dex classes.
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -q "$APK" 'lib/*' classes.dex -d "$TMP"

case $ABI in
    arm64-v8a) ELF_MACHINE="ARM aarch64"; READELF_MACHINE="AArch64" ;;
    x86_64) ELF_MACHINE="x86-64"; READELF_MACHINE="Advanced Micro Devices X86-64" ;;
    *) fail "unknown ABI $ABI" ;;
esac
for so in "$TMP/lib/$ABI"/*.so; do
    if command -v file >/dev/null; then
        file "$so" | grep -q "ELF 64-bit LSB shared object, $ELF_MACHINE" ||
            fail "$(basename "$so") is not a 64-bit $ABI shared object: $(file "$so")"
    elif command -v readelf >/dev/null; then
        readelf -h "$so" | grep -q "Machine:.*$READELF_MACHINE" ||
            fail "$(basename "$so") has the wrong ELF machine (want $READELF_MACHINE)"
    else
        echo "verify-apk: note: no file/readelf in PATH, skipping ELF architecture check"
        break
    fi
done
ok "ELF architecture ($ABI)"

if command -v readelf >/dev/null; then
    # Every library libmain.so needs must ship in the APK or be a public Android system library.
    SYSTEM_LIBS='^lib(c|m|dl|log|android|z|vulkan|EGL|GLESv1_CM|GLESv2|GLESv3|OpenSLES|aaudio|mediandk|jnigraphics|camera2ndk)\.so$'
    while IFS= read -r needed; do
        [ -n "$needed" ] || continue
        [ -e "$TMP/lib/$ABI/$needed" ] || grep -qE "$SYSTEM_LIBS" <<<"$needed" ||
            fail "libmain.so needs $needed, which is neither bundled nor a known system library"
    done < <(readelf -d "$TMP/lib/$ABI/libmain.so" | sed -n 's/.*Shared library: \[\(.*\)\].*/\1/p')
    ok "libmain.so dependencies"
else
    echo "verify-apk: note: no readelf in PATH, skipping dependency check"
fi

grep -a -q 'com/protore/game/SetupActivity' "$TMP/classes.dex" || fail "SetupActivity missing from classes.dex"
grep -a -q 'com/protore/game/ProtoActivity' "$TMP/classes.dex" || fail "ProtoActivity missing from classes.dex"
grep -a -q 'org/libsdl/app/SDLActivity' "$TMP/classes.dex" || fail "SDLActivity missing from classes.dex"
ok "dex classes"

echo "verify-apk: PASSED $APK ($(du -h "$APK" | cut -f1), ABI $ABI)"
unzip -l "$APK" | grep -E 'lib/.*\.so$' || true
