#!/usr/bin/env bash
# Checks that nothing private is about to be published: run before every
# commit/push. Exits 1 if it finds anything.
#
#   scripts/check-leaks.sh            check tracked + staged files
#   scripts/check-leaks.sh --history  also scan every commit in the history
#   scripts/check-leaks.sh --install-hook   run it automatically before each commit
#
# Your own private strings (domains, hosts, names, IPs...) go in the git-ignored
# file local/private-patterns.txt, one extended regex per line (# for comments).
set -uo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

if [[ "${1:-}" == "--install-hook" ]]; then
  hook="$(git rev-parse --git-dir)/hooks/pre-commit"
  printf '#!/bin/sh\nexec "$(git rev-parse --show-toplevel)/scripts/check-leaks.sh"\n' >"$hook"
  chmod +x "$hook"
  echo "Installed $hook"
  exit 0
fi

fail=0
report() { echo "✗ $1"; shift; printf '%s\n' "$@" | head -20 | sed 's/^/    /'; fail=1; }

# Files that would be published: tracked + staged, minus deletions.
files=()
while IFS= read -r f; do [[ -f "$f" ]] && files+=("$f"); done < <(
  { git ls-files; git diff --cached --name-only --diff-filter=d; } | sort -u)

text_files=()
for f in "${files[@]}"; do
  case "$f" in
    *.jar|*.ttf|*.png|*.jpg|*.webp|*.apk|*.aab) ;;
    *) text_files+=("$f") ;;
  esac
done

# 1. Files that must never be tracked.
bad=$(printf '%s\n' "${files[@]}" | grep -E '(^|/)(local/|\.env($|\.)|.*\.local\.|local\.properties$)|\.(jks|keystore|p12|pem|key)$|(^|/)\.claude/settings\.local\.json$' || true)
[[ -n "$bad" ]] && report "Private files are tracked or staged:" $bad

# Scans file contents for an extended regex; prints file:line:match.
scan() { grep -nIoE "$1" -- "${text_files[@]}" 2>/dev/null | grep -vE "$2" || true; }
NONE='^$'

# 2. Secrets.
hits=$(scan '-----BEGIN [A-Z ]*PRIVATE KEY-----|ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{40,}|sk-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{35}' "$NONE")
[[ -n "$hits" ]] && report "Possible secrets or private keys:" "$hits"

hits=$(scan '(INSTALL_TOKEN|storePassword|keyPassword|password|secret|token)[[:space:]]*[=:][[:space:]]*["'"'"']?[A-Za-z0-9+/_-]{16,}' 'testdata/|_test\.go|/test/')
[[ -n "$hits" ]] && report "Hard-coded credential-looking assignments:" "$hits"

# 3. Personal paths and e-mail addresses. Your real home directory is always
# flagged, even if its name looks generic.
hits=$(grep -nIF -- "$HOME/" "${text_files[@]}" 2>/dev/null | cut -c1-160 || true)
[[ -n "$hits" ]] && report "Your home directory ($HOME):" "$hits"
hits=$(scan '(/Users|/home)/[A-Za-z0-9._-]+/' '(/Users|/home)/(you|user|runner|me|name)/')
[[ -n "$hits" ]] && report "Absolute home-directory paths:" "$hits"

hits=$(scan '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' '@(example\.(com|org|net)|users\.noreply\.github\.com)|git@github\.com|@[0-9]+(\.[0-9]+)*$')
[[ -n "$hits" ]] && report "E-mail addresses:" "$hits"

# 4. IPv4 addresses other than well-known or documentation/test ones.
hits=$(scan '([0-9]{1,3}\.){3}[0-9]{1,3}' ':(127\.0\.0\.1|0\.0\.0\.0|255\.255\.255\.255|100\.64\.0\.0|100\.63\.0\.1|100\.100\.1\.1|100\.100\.100\.100|100\.101\.1\.2|192\.168\.1\.5|192\.0\.2\.[0-9]+|198\.51\.100\.[0-9]+|203\.0\.113\.[0-9]+)$' |
  grep -vE '(go\.sum|gradle-wrapper\.properties|build\.gradle\.kts|settings\.gradle\.kts):' |
  grep -vE ':(127\.0\.0\.1|0\.0\.0\.0|100\.64\.0\.0|100\.63\.0\.1|100\.100\.1\.1|100\.100\.100\.100|100\.101\.1\.2|192\.168\.1\.5)$' || true)
# Keep only matches that are valid addresses (every octet <= 255).
hits=$(echo "$hits" | awk -F: 'NF>=3 { split($NF, o, "."); if (o[1]<=255 && o[2]<=255 && o[3]<=255 && o[4]<=255) print }')
[[ -n "$hits" ]] && report "IP addresses (add documentation/test ones to the allow-list if intended):" "$hits"

# 5. Your own private strings.
patterns="local/private-patterns.txt"
if [[ -f "$patterns" ]]; then
  while IFS= read -r pat; do
    [[ -z "$pat" || "$pat" == \#* ]] && continue
    hits=$(grep -nIiE -- "$pat" "${text_files[@]}" 2>/dev/null | cut -c1-160 || true)
    [[ -n "$hits" ]] && report "Matches private pattern '$pat':" "$hits"
  done <"$patterns"
else
  echo "! $patterns not found: only generic checks ran. Create it with your private domains, hosts, names and IPs."
fi

# 6. Optionally, the whole history (catches things committed and later removed).
if [[ "${1:-}" == "--history" && -f "$patterns" ]] && git rev-parse HEAD >/dev/null 2>&1; then
  while IFS= read -r pat; do
    [[ -z "$pat" || "$pat" == \#* ]] && continue
    hits=$(git log --all -p -i -E -G "$pat" --format='%h %s' 2>/dev/null | grep -iE -- "$pat" | head -5 || true)
    [[ -n "$hits" ]] && report "History contains private pattern '$pat':" "$hits"
  done <"$patterns"
fi

if ((fail)); then
  echo
  echo "Private data found. Remove it (or move it to local/) before publishing."
  exit 1
fi
echo "✓ No private data found in ${#files[@]} files."
