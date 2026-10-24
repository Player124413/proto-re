#!/bin/bash
# Packages the APK without Gradle: SDL's Java activity + ours -> classes.dex, the manifest -> aapt2, native
# libraries for the requested ABIs, then zipalign and apksigner. Signing defaults to the committed
# android/debug.keystore, so successive builds install as updates over each other (no wipe on update).
#
# Usage: android/build-apk.sh [abi ...]          (default: arm64-v8a)
# Needs: build/android/<abi>/libmain.so (make -C src/native PLATFORM=android ABI=<abi>) and the libraries from
# android/build-deps.sh in $DEPS_ROOT/<abi>/lib. Output: build/apk/prototype.apk
# Environment (all optional; sensible SDK defaults are auto-detected):
#   ANDROID_HOME (or ANDROID_SDK_ROOT), BUILD_TOOLS, PLATFORM_JAR, ANDROID_NDK_HOME,
#   DEPS_ROOT, TURNIP_ROOT, SDL_SRC, KEYSTORE, KEYSTORE_TYPE, KEYSTORE_PASSWORD, KEY_PASSWORD,
#   KEY_ALIAS, VERSION_CODE, VERSION_NAME, STRIP.
set -euo pipefail

die() { echo "build-apk: $*" >&2; exit 1; }

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
# Prefer the versions this project targets, fall back to the newest installed one (CI images differ).
if [ -z "${BUILD_TOOLS:-}" ]; then
    if [ -d "$SDK/build-tools/36.0.0" ]; then
        BUILD_TOOLS=$SDK/build-tools/36.0.0
    else
        BUILD_TOOLS=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)
        BUILD_TOOLS=${BUILD_TOOLS%/}
    fi
fi
if [ -z "${PLATFORM_JAR:-}" ]; then
    if [ -f "$SDK/platforms/android-35/android.jar" ]; then
        PLATFORM_JAR=$SDK/platforms/android-35/android.jar
    else
        PLATFORM_JAR=$(find "$SDK/platforms" -mindepth 2 -maxdepth 2 -name android.jar 2>/dev/null | sort -V | tail -n 1 || true)
    fi
fi
NDK_ROOT=${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}
if [ -z "$NDK_ROOT" ]; then
    NDK_ROOT=$(find "$SDK/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)
    NDK_ROOT=${NDK_ROOT%/}
fi
STRIP=${STRIP:-$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip}
DEPS_ROOT=${DEPS_ROOT:-$HOME/proto-re-data/android-deps}
TURNIP_ROOT=${TURNIP_ROOT:-$HOME/proto-re-data/android-turnip}
SDL_SRC=${SDL_SRC:-$HOME/proto-re-data/android-deps-src/SDL2}
# One committed debug key for every build (public by design - it is a debug key, never use it for a
# store release). The same certificate, plus an increasing VERSION_CODE from the caller, is what lets
# Android install a new APK over the old one without wiping /sdcard/Android/data/com.protore.game.
# Point KEYSTORE/KEYSTORE_PASSWORD/KEY_PASSWORD/KEY_ALIAS at your own key if you want private signing.
KEYSTORE=${KEYSTORE:-$ROOT/android/debug.keystore}
KEYSTORE_PASSWORD=${KEYSTORE_PASSWORD:-android}
KEY_PASSWORD=${KEY_PASSWORD:-$KEYSTORE_PASSWORD}
KEY_ALIAS=${KEY_ALIAS:-androiddebugkey}
export KEYSTORE_PASSWORD KEY_PASSWORD
if [ -z "${KEYSTORE_TYPE:-}" ]; then
    case "$KEYSTORE" in
        *.p12|*.pfx) KEYSTORE_TYPE=PKCS12 ;;
        *.jks) KEYSTORE_TYPE=JKS ;;
        *) KEYSTORE_TYPE="" ;;
    esac
fi
ABIS=("${@:-arm64-v8a}")

# Fail early with a useful message instead of an obscure tool error halfway through.
command -v javac >/dev/null || die "javac not found in PATH (install a JDK)"
command -v zip >/dev/null || die "zip not found in PATH"
[ -n "$BUILD_TOOLS" ] || die "no Android build-tools under $SDK/build-tools (set ANDROID_HOME or BUILD_TOOLS)"
for tool in d8 aapt2 zipalign apksigner; do
    [ -x "$BUILD_TOOLS/$tool" ] || die "$BUILD_TOOLS/$tool not found (set BUILD_TOOLS)"
done
[ -n "$PLATFORM_JAR" ] && [ -f "$PLATFORM_JAR" ] ||
    die "android.jar not found under $SDK/platforms (set ANDROID_HOME or PLATFORM_JAR)"
[ -d "$SDL_SRC/android-project/app/src/main/java/org/libsdl/app" ] ||
    die "SDL2 Java sources not found in $SDL_SRC (set SDL_SRC; android/build-deps.sh clones SDL2)"
for abi in "${ABIS[@]}"; do
    for f in "$ROOT/build/android/$abi/libmain.so" "$DEPS_ROOT/$abi"/lib/lib{SDL2,FAudio,dxvk_d3d9}.so; do
        [ -f "$f" ] || die "missing $f (build the runtime for $abi and android/build-deps.sh first)"
    done
done
if [ ! -f "$KEYSTORE" ]; then
    command -v keytool >/dev/null || die "keytool not found and no keystore at $KEYSTORE"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool_args=(-genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" \
        -keypass "$KEY_PASSWORD" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10950 \
        -dname "CN=Android Debug,O=Android,C=US")
    if [ -n "$KEYSTORE_TYPE" ]; then
        keytool_args+=(-storetype "$KEYSTORE_TYPE")
    fi
    keytool "${keytool_args[@]}" >/dev/null
    echo "build-apk: created debug keystore $KEYSTORE"
fi

OUT=$ROOT/build/apk
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/pkg"

# Java: SDL's activity classes plus ProtoActivity.
javac --release 11 -nowarn -cp "$PLATFORM_JAR" -d "$OUT/classes" \
    "$SDL_SRC"/android-project/app/src/main/java/org/libsdl/app/*.java "$ROOT"/android/java/com/protore/game/*.java
mapfile -d '' CLASS_FILES < <(find "$OUT/classes" -name '*.class' -print0)
"$BUILD_TOOLS/d8" --release --min-api 26 --lib "$PLATFORM_JAR" --output "$OUT/dex" \
    "${CLASS_FILES[@]}"

# Resources (launcher icon; tools/make_icon.py draws it). CI passes a fresh VERSION_CODE (epoch
# seconds) for every build: Android only treats an APK as an update when the signature matches and
# the versionCode does not go backwards. Local builds keep the manifest values unless overridden.
VERSION_ARGS=()
if [ -n "${VERSION_CODE:-}" ]; then
    case "$VERSION_CODE" in
        *[!0-9]*) die "VERSION_CODE must be numeric, got '$VERSION_CODE'" ;;
    esac
    VERSION_ARGS+=(--version-code "$VERSION_CODE")
fi
if [ -n "${VERSION_NAME:-}" ]; then
    VERSION_ARGS+=(--version-name "$VERSION_NAME")
fi
"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/android/res" -o "$OUT/res.zip"
"$BUILD_TOOLS/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM_JAR" --manifest "$ROOT/android/AndroidManifest.xml" \
    -R "$OUT/res.zip" --auto-add-overlay --min-sdk-version 26 --target-sdk-version 35 "${VERSION_ARGS[@]}"

cp "$OUT/dex/classes.dex" "$OUT/pkg/"
for abi in "${ABIS[@]}"; do
    mkdir -p "$OUT/pkg/lib/$abi"
    cp "$ROOT/build/android/$abi/libmain.so" "$DEPS_ROOT/$abi"/lib/lib{SDL2,FAudio,dxvk_d3d9}.so "$OUT/pkg/lib/$abi/"
    # Adreno: Turnip and the adrenotools hooks (android/build-turnip.sh), when built
    if [ "$abi" = arm64-v8a ] && [ -e "$TURNIP_ROOT/lib/libvulkan_freedreno.so" ]; then
        cp "$TURNIP_ROOT"/lib/*.so "$OUT/pkg/lib/$abi/"
    fi
    if [ -x "$STRIP" ]; then
        "$STRIP" --strip-unneeded "$OUT/pkg/lib/$abi"/*.so
    else
        echo "build-apk: llvm-strip not found ($STRIP), shipping unstripped libraries" >&2
    fi
done
(cd "$OUT/pkg" && zip -q -r "$OUT/base.apk" classes.dex lib)

"$BUILD_TOOLS/zipalign" -P 16 -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
SIGN_ARGS=(--ks "$KEYSTORE" --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
    --ks-key-alias "$KEY_ALIAS")
if [ -n "$KEYSTORE_TYPE" ]; then
    SIGN_ARGS+=(--ks-type "$KEYSTORE_TYPE")
fi
"$BUILD_TOOLS/apksigner" sign "${SIGN_ARGS[@]}" --out "$OUT/prototype.apk" "$OUT/aligned.apk"
ls -la "$OUT/prototype.apk"
