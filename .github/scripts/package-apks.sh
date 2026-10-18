#!/usr/bin/env bash
set -euo pipefail

artifact_dir="${RUNNER_TEMP:?RUNNER_TEMP must be set}/release-apks"
build_tools="${ANDROID_HOME:?ANDROID_HOME must be set}/build-tools/36.0.0"
unsigned_apk="app/build/outputs/apk/release/app-release-unsigned.apk"
debug_apk="app/build/outputs/apk/debug/app-debug.apk"

required_secrets=(
  ANDROID_KEYSTORE_BASE64
  ANDROID_KEYSTORE_PASSWORD
  ANDROID_KEY_ALIAS
  ANDROID_KEY_PASSWORD
)

has_signing_secrets=false
for name in "${required_secrets[@]}"; do
  if [[ -n "${!name:-}" ]]; then
    has_signing_secrets=true
  fi
done

if [[ "$has_signing_secrets" == true ]]; then
  for name in "${required_secrets[@]}"; do
    if [[ -z "${!name:-}" ]]; then
      echo "::error::Missing signing secret: $name. Configure all four signing secrets or leave all unset."
      exit 1
    fi
  done
fi

mkdir -p "$artifact_dir"
cp "$debug_apk" "$artifact_dir/Msngr-debug.apk"
cp "$unsigned_apk" "$artifact_dir/Msngr-release-unsigned.apk"
java -jar "$build_tools/lib/apksigner.jar" verify "$artifact_dir/Msngr-debug.apk"

if [[ "$has_signing_secrets" == true ]]; then
  umask 077
  keystore_file="$(mktemp "$RUNNER_TEMP/msngr-release-XXXXXX.jks")"
  trap 'rm -f -- "$keystore_file"' EXIT
  printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$keystore_file"

  "$build_tools/zipalign" -P 16 -f 4 "$unsigned_apk" "$RUNNER_TEMP/msngr-release-aligned.apk"
  java -jar "$build_tools/lib/apksigner.jar" sign \
    --ks "$keystore_file" \
    --ks-pass env:ANDROID_KEYSTORE_PASSWORD \
    --ks-key-alias "$ANDROID_KEY_ALIAS" \
    --key-pass env:ANDROID_KEY_PASSWORD \
    --out "$artifact_dir/Msngr-release.apk" \
    "$RUNNER_TEMP/msngr-release-aligned.apk"
  java -jar "$build_tools/lib/apksigner.jar" verify --verbose "$artifact_dir/Msngr-release.apk"
  signing_status="Release APK signed with the configured release key."
else
  signing_status="Release APK is unsigned and must be signed before installation. Configure the four Android signing secrets to publish a signed release APK."
  echo "::notice::$signing_status"
fi

(
  cd "$artifact_dir"
  sha256sum -- *.apk > SHA256SUMS.txt
)

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    printf '### Release assets\n\n%s\n\n' "$signing_status"
    printf -- '- `Msngr-debug.apk` (debug signed)\n'
    if [[ "$has_signing_secrets" == true ]]; then
      printf -- '- `Msngr-release.apk`\n'
    fi
    printf -- '- `Msngr-release-unsigned.apk`\n'
    printf -- '- `SHA256SUMS.txt`\n'
  } >> "$GITHUB_STEP_SUMMARY"
fi
