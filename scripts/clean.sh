#!/usr/bin/env bash
# Frees the disk space used by building sublime-sync. Asks before each step.
# Usage: scripts/clean.sh            (interactive)
#        scripts/clean.sh --yes      (project build outputs only, no questions)
# Never touches your installed agent, its data, the server or your keys.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
auto=false
[[ "${1:-}" == "--yes" ]] && auto=true

size() { du -shc "$@" 2>/dev/null | tail -1 | cut -f1; }

# ask "question" default(y|n) -> returns 0 for yes
ask() {
  local q="$1" def="$2" ans
  if $auto; then [[ "$def" == y ]]; return; fi
  local hint="[s/N]"; [[ "$def" == y ]] && hint="[S/n]"
  read -r -p "$q $hint " ans || true
  ans="$(echo "${ans:-$def}" | tr '[:upper:]' '[:lower:]')"
  [[ "$ans" == s* || "$ans" == y* ]]
}

echo "sublime-sync: limpieza de compilación / build cleanup"
echo

# 1. Project build outputs (safe; recreated by the next build).
outputs=()
for d in "$root/android/app/build" "$root/android/build" "$root/android/.gradle" "$root/android/.kotlin" "$root/bin" "$root/dist"; do
  [[ -e "$d" ]] && outputs+=("$d")
done
if ((${#outputs[@]})); then
  echo "Salidas de compilación del proyecto: $(size "${outputs[@]}")"
  apk="$root/android/app/build/outputs/apk/release/app-release.apk"
  if [[ -f "$apk" ]] && ask "  ¿Guardar antes el APK release en ~/Downloads?" y; then
    cp "$apk" "$HOME/Downloads/sublime-sync-$(date +%Y%m%d-%H%M).apk"
    echo "  APK copiado a ~/Downloads"
  fi
  if ask "  ¿Borrarlas?" y; then
    (cd "$root/android" && ./gradlew --stop >/dev/null 2>&1) || true
    rm -rf "${outputs[@]}"
    echo "  Borradas."
  fi
fi
find "$root/plugin" -name __pycache__ -type d -prune -exec rm -rf {} + 2>/dev/null || true

$auto && exit 0
echo

# 2. Shared caches: other projects on this Mac may use them too.
if [[ -d "$HOME/.gradle" ]]; then
  echo "Caché de Gradle/Android en ~/.gradle: $(size "$HOME/.gradle")"
  echo "  Compartida con otros proyectos Android; se vuelve a descargar al compilar."
  if ask "  ¿Borrar sus cachés (caches/, wrapper/, daemon/)?" n; then
    pkill -f GradleDaemon 2>/dev/null || true
    rm -rf "$HOME/.gradle/caches" "$HOME/.gradle/wrapper" "$HOME/.gradle/daemon" "$HOME/.gradle/native"
    echo "  Borradas."
  fi
fi

if command -v go >/dev/null; then
  modcache="$(go env GOMODCACHE)"; buildcache="$(go env GOCACHE)"
  echo "Cachés de Go: módulos $(size "$modcache"), compilación $(size "$buildcache")"
  echo "  Compartidas con otros proyectos Go."
  if ask "  ¿Borrarlas?" n; then
    go clean -modcache -cache
    echo "  Borradas."
  fi
fi

# 3. Homebrew Gradle (optional; the project uses its own wrapper, android/gradlew).
if command -v brew >/dev/null && brew list --versions gradle >/dev/null 2>&1; then
  echo "Gradle de Homebrew: $(size "$(brew --prefix)/Cellar/gradle")"
  echo "  No hace falta para compilar (el proyecto trae android/gradlew)."
  if ask "  ¿Desinstalarlo?" n; then
    brew uninstall gradle
  else
    echo "  Se mantiene."
  fi
fi

echo
echo "Listo. Nada de esto afecta al agente instalado, a sus datos ni a tus claves."
