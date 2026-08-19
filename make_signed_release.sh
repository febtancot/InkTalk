#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
KEYSTORE_PATH="$PROJECT_DIR/release/inktalk-release.jks"
KEY_ALIAS="inktalk-release"
STORE_SERVICE="com.inktalk.ime.release.store"
KEY_SERVICE="com.inktalk.ime.release.key"
KEYCHAIN_ACCOUNT="inktalk-release"

if [[ ! -f "$KEYSTORE_PATH" ]]; then
    echo "缺少 Release 密钥：$KEYSTORE_PATH" >&2
    exit 1
fi

STORE_PASSWORD="$(security find-generic-password -a "$KEYCHAIN_ACCOUNT" -s "$STORE_SERVICE" -w)"
KEY_PASSWORD="$(security find-generic-password -a "$KEYCHAIN_ACCOUNT" -s "$KEY_SERVICE" -w)"

cd "$PROJECT_DIR"
./gradlew testDebugUnitTest assembleRelease lintRelease

SDK_DIR="$(sed -n 's/^sdk.dir=//p' local.properties)"
BUILD_TOOLS="$(find "$SDK_DIR/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)"
VERSION_NAME="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
if [[ -z "$VERSION_NAME" ]]; then
    echo "无法从 app/build.gradle.kts 读取 versionName" >&2
    exit 1
fi
UNSIGNED_APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
SIGNED_APK="$PROJECT_DIR/app/build/outputs/apk/release/InkTalk-$VERSION_NAME-release.apk"
ALIGNED_APK="$(mktemp "${TMPDIR:-/tmp}/inktalk-release-aligned.XXXXXX")"
trap 'rm -f "$ALIGNED_APK"' EXIT

"$BUILD_TOOLS/zipalign" -f -P 16 4 "$UNSIGNED_APK" "$ALIGNED_APK"
INKTALK_STORE_PASSWORD="$STORE_PASSWORD" INKTALK_KEY_PASSWORD="$KEY_PASSWORD" \
    "$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE_PATH" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:INKTALK_STORE_PASSWORD \
    --key-pass env:INKTALK_KEY_PASSWORD \
    --out "$SIGNED_APK" \
    "$ALIGNED_APK"

"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$SIGNED_APK"
"$BUILD_TOOLS/zipalign" -c -P 16 4 "$SIGNED_APK"
shasum -a 256 "$SIGNED_APK"

echo "已生成：$SIGNED_APK"
