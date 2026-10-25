#!/usr/bin/env bash
# Builds the signed release APK into dist/. Usage: tools/release.sh [extra Gradle arguments]
#   Version overrides (validated by app/build.gradle.kts): -PsortieVersionCode=3 -PsortieVersionName=0.1.0-beta.2
# Signing key: $SORTIE_SIGNING_DIR (default ${XDG_DATA_HOME:-$HOME/.local/share}/sortie/signing, made by
# tools/init-release-key.sh), or all of SORTIE_RELEASE_KEYSTORE, SORTIE_RELEASE_KEYSTORE_PASSWORD_FILE and
# SORTIE_RELEASE_KEY_ALIAS. There is no debug-key fallback: the APK must verify as signed by exactly that key, carry the
# expected package and not be debuggable; only then are dist/sortie-<versionName>-code<versionCode>.apk and its .sha256 written.
# No formatting, publishing or upload. Uses the host JDK/SDK when java is on PATH, else the Ubuntu distrobox (like tools/build.sh).
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
repo=$(pwd -P)
package=io.github.esko997.sortie

die() {
  printf 'release: %s\n' "$*" >&2
  exit 1
}

# A signing file must exist, live outside the repository and be private to its owner, as must its directory.
private_file() {
  local path=$1 real p mode
  [ -f "$path" ] && [ -r "$path" ] || die "$path is missing or unreadable; create the key once with tools/init-release-key.sh"
  real=$(realpath -- "$path")
  case "$real/" in "$repo"/*) die "$path is inside the repository; keep signing files out of it" ;; esac
  for p in "$real" "$(dirname -- "$real")"; do
    [ "$(stat -c %u -- "$p")" = "$(id -u)" ] || die "$p is not owned by you"
    mode=$(stat -c %a -- "$p")
    (( (8#$mode & 8#077) == 0 )) || die "$p is accessible to other users (mode $mode); remove group/other access (chmod go=)"
  done
  printf '%s\n' "$real"
}

if [ -n "${SORTIE_RELEASE_KEYSTORE:-}${SORTIE_RELEASE_KEYSTORE_PASSWORD_FILE:-}${SORTIE_RELEASE_KEY_ALIAS:-}" ]; then
  if [ -z "${SORTIE_RELEASE_KEYSTORE:-}" ] || [ -z "${SORTIE_RELEASE_KEYSTORE_PASSWORD_FILE:-}" ] || [ -z "${SORTIE_RELEASE_KEY_ALIAS:-}" ]; then
    die 'set all of SORTIE_RELEASE_KEYSTORE, SORTIE_RELEASE_KEYSTORE_PASSWORD_FILE and SORTIE_RELEASE_KEY_ALIAS, or none of them'
  fi
  keystore=$SORTIE_RELEASE_KEYSTORE
  passfile=$SORTIE_RELEASE_KEYSTORE_PASSWORD_FILE
  alias=$SORTIE_RELEASE_KEY_ALIAS
else
  dir=${SORTIE_SIGNING_DIR:-${XDG_DATA_HOME:-$HOME/.local/share}/sortie/signing}
  keystore=$dir/sortie-release.p12
  passfile=$dir/store-password
  alias=sortie-release
fi
keystore=$(private_file "$keystore")
passfile=$(private_file "$passfile")
[[ $alias =~ ^[A-Za-z0-9._-]+$ ]] || die "key alias \"$alias\" may only contain letters, digits, '.', '_' and '-'"
# One line, no carriage return, at least keytool's minimum length; checked without reading it into this shell.
awk 'NR == 1 { n = length($0) } /\r/ { bad = 1 } END { exit !(NR == 1 && n >= 6 && !bad) }' "$passfile" ||
  die "$passfile must hold exactly one line: the keystore password"

if command -v java >/dev/null 2>&1; then
  launcher=()
elif command -v distrobox >/dev/null 2>&1; then
  launcher=(distrobox enter ubuntu --)
else
  printf '%s\n' 'Install JDK 17 and the Android SDK, or provide the Ubuntu distrobox used by tools/build.sh.' >&2
  exit 127
fi

# Pin verifier output/behavior to the build-tools version installed by .github/workflows/build.yml.
in_sdk() {
  "${launcher[@]}" bash -c '
    set -euo pipefail
    cd -- "$1"
    shift
    if [ -z "${ANDROID_HOME:-}" ]; then
      ANDROID_HOME=${ANDROID_SDK_ROOT:-}
      if [ -z "$ANDROID_HOME" ] && [ -f local.properties ]; then ANDROID_HOME=$(sed -n "s/^sdk\.dir=//p" local.properties | tail -n 1); fi
      ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
    fi
    export ANDROID_HOME
    tools=$ANDROID_HOME/build-tools/35.0.0
    [ -x "$tools/apksigner" ] && [ -x "$tools/aapt2" ] ||
      { printf "release: install Android build-tools;35.0.0 under %s\n" "$ANDROID_HOME" >&2; exit 127; }
    PATH=$tools:$PATH
    exec "$@"' sortie-release "$repo" "$@"
}

# Fresh daemon, no configuration or build cache: the password is read by Gradle and must not outlive this build.
apk=app/build/outputs/apk/release/app-release.apk
rm -f -- "$apk"
in_sdk env SORTIE_RELEASE_KEYSTORE="$keystore" SORTIE_RELEASE_KEYSTORE_PASSWORD_FILE="$passfile" SORTIE_RELEASE_KEY_ALIAS="$alias" \
  ./gradlew --console=plain --no-daemon --no-configuration-cache --no-build-cache assembleRelease "$@"
[ -f "$apk" ] || die "the build produced no signed $apk; refusing to package an unsigned or debug-signed APK"

signers=$(in_sdk apksigner verify --verbose --print-certs "$apk")
grep -qx 'Number of signers: 1' <<<"$signers" || die "$apk must have exactly one signer"
grep -qx 'Verified using v2 scheme (APK Signature Scheme v2): true' <<<"$signers" || die "$apk lacks a valid v2 signature"
apk_cert=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$signers")
key_cert=$(in_sdk keytool -list -v -keystore "$keystore" -storetype PKCS12 -alias "$alias" -storepass:file "$passfile" </dev/null |
  awk '/^[[:space:]]*SHA256: / && !seen { seen = 1; sub(/^[[:space:]]*SHA256: /, ""); gsub(/:/, ""); print tolower($0) }')
[ -n "$key_cert" ] || die "could not read the certificate of \"$alias\" in $keystore"
[ "$apk_cert" = "$key_cert" ] || die "$apk is signed by certificate ${apk_cert:-unknown}, not the release key $key_cert"

badging=$(in_sdk aapt2 dump badging "$apk")
manifest=$(grep -m 1 '^package: ' <<<"$badging") || die "aapt2 could not read the package of $apk"
name=$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<<"$manifest")
code=$(sed -n "s/.* versionCode='\([^']*\)'.*/\1/p" <<<"$manifest")
[ "$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$manifest")" = "$package" ] || die "$apk is not $package"
if grep -q '^application-debuggable' <<<"$badging"; then die "$apk is debuggable"; fi
[[ $code =~ ^[1-9][0-9]*$ ]] || die "unexpected versionCode \"$code\""
[[ $name =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || die "unexpected versionName \"$name\""

# The password must not have been written anywhere by the build (outputs, project caches, Gradle daemon logs).
scan=()
for p in app/build build .gradle "${GRADLE_USER_HOME:-$HOME/.gradle}/daemon"; do
  if [ -e "$p" ]; then scan+=("$p"); fi
done
status=0
leaks=$(grep -rlsF -f "$passfile" -- "${scan[@]}") || status=$?
[ "$status" -ne 0 ] || die "the signing password was found in: $(tr '\n' ' ' <<<"$leaks")- delete those files"
[ "$status" -eq 1 ] || die "could not scan the build output for the signing password"

out=sortie-$name-code$code.apk
mkdir -p dist
if [ -e "dist/$out" ]; then
  cmp -s -- "$apk" "dist/$out" ||
    die "dist/$out already exists with different contents; raise -PsortieVersionCode/-PsortieVersionName or move the old file away"
else
  cp -- "$apk" "dist/.$out.tmp"
  mv -- "dist/.$out.tmp" "dist/$out"
fi
(cd dist && sha256sum -- "$out" >".$out.sha256.tmp" && mv -- ".$out.sha256.tmp" "$out.sha256")

printf 'Signed release: dist/%s (versionCode %s, versionName %s)\n' "$out" "$code" "$name"
printf '  APK SHA-256:         %s (dist/%s.sha256)\n' "$(cut -d ' ' -f 1 "dist/$out.sha256")" "$out"
printf '  signer certificate:  %s\n' "$apk_cert"
