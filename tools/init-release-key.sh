#!/usr/bin/env bash
# Creates Sortie's persistent release signing key, once. Usage: tools/init-release-key.sh [directory]
# Directory: the argument, else $SORTIE_SIGNING_DIR, else ${XDG_DATA_HOME:-$HOME/.local/share}/sortie/signing; it must be
# outside the repository and is kept owner-only. Writes sortie-release.p12 (PKCS12, RSA 3072, alias sortie-release) and
# store-password (256 random bits as hex; also the key password). The password never appears in output or on a command line.
# It refuses to replace or rotate an existing key: every update of an installed Sortie must be signed with this same key, so
# back up BOTH files together, offline, before distributing a build. tools/release.sh reads them from the same directory.
set -euo pipefail
umask 077

die() {
  printf 'init-release-key: %s\n' "$*" >&2
  exit 1
}

repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
[ $# -le 1 ] || die 'usage: tools/init-release-key.sh [directory]'
default_dir=${XDG_DATA_HOME:-$HOME/.local/share}/sortie/signing
dir=$(realpath -m -- "${1:-${SORTIE_SIGNING_DIR:-$default_dir}}")
case "$dir/" in "$repo"/*) die "$dir is inside the repository; choose a directory outside it" ;; esac
keystore=$dir/sortie-release.p12
passfile=$dir/store-password
alias=sortie-release

for f in "$keystore" "$passfile"; do
  if [ -e "$f" ] || [ -L "$f" ]; then
    die "$f already exists; refusing to overwrite or rotate the release key (installed copies only accept updates signed by it)"
  fi
done

if command -v keytool >/dev/null 2>&1; then
  keytool=(keytool)
elif command -v distrobox >/dev/null 2>&1; then
  keytool=(distrobox enter ubuntu -- keytool)
else
  printf '%s\n' 'Install JDK 17 (keytool), or provide the Ubuntu distrobox used by tools/build.sh.' >&2
  exit 127
fi

mkdir -p -- "$dir"
[ "$(stat -c %u -- "$dir")" = "$(id -u)" ] || die "$dir is not owned by you"
mode=$(stat -c %a -- "$dir")
(( (8#$mode & 8#077) == 0 )) || die "$dir is accessible to other users (mode $mode); run chmod 700 on it first"

# Generate into a private staging directory, then publish with hard links, which fail rather than replace an existing file.
stage=$(mktemp -d -p "$dir" .init-XXXXXX)
trap 'rm -rf -- "$stage"' EXIT
od -An -v -tx1 -N32 /dev/urandom | tr -d ' \n' >"$stage/store-password"
[ "$(wc -c <"$stage/store-password")" -eq 64 ] || die 'could not generate a random password'
"${keytool[@]}" -genkeypair -keystore "$stage/sortie-release.p12" -storetype PKCS12 \
  -storepass:file "$stage/store-password" -keypass:file "$stage/store-password" \
  -alias "$alias" -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=Sortie, OU=Release' </dev/null
chmod 600 -- "$stage/store-password" "$stage/sortie-release.p12"
fingerprint=$("${keytool[@]}" -list -v -keystore "$stage/sortie-release.p12" -storetype PKCS12 -alias "$alias" \
  -storepass:file "$stage/store-password" </dev/null | sed -n 's/^[[:space:]]*SHA256: //p')
[ -n "$fingerprint" ] || die 'could not read the new certificate fingerprint; nothing was published'
sync -- "$stage/store-password" "$stage/sortie-release.p12"

ln -- "$stage/store-password" "$passfile" || die "$passfile appeared meanwhile; nothing was replaced"
if ! ln -- "$stage/sortie-release.p12" "$keystore"; then
  if [ "$passfile" -ef "$stage/store-password" ]; then rm -f -- "$passfile"; fi
  die "$keystore appeared meanwhile; nothing was replaced"
fi
sync -- "$dir"

printf 'Created the Sortie release key (RSA 3072, PKCS12, alias %s).\n' "$alias"
printf '  keystore:            %s\n' "$keystore"
printf '  password file:       %s\n' "$passfile"
printf '  certificate SHA-256: %s\n' "$fingerprint"
printf '%s\n' 'Back up BOTH files together now, offline (for example an encrypted drive). Losing either means installed copies' \
  'can never be updated; anyone holding both can sign updates as Sortie. Never commit, share or upload them.'
if [ "$dir" = "$(realpath -m -- "$default_dir")" ]; then
  printf '%s\n' 'Build a signed release with: tools/release.sh'
else
  printf 'Build a signed release with: SORTIE_SIGNING_DIR=%q tools/release.sh\n' "$dir"
fi
