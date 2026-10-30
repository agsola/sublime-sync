# Limpiar después de compilar

[English](cleanup.md) · **Español**

Compilar sublime-sync ocupa disco en varios sitios. `scripts/clean.sh` lo libera y
pregunta antes de cada paso:

```sh
scripts/clean.sh          # interactivo
scripts/clean.sh --yes    # solo las salidas de compilación de este proyecto, sin preguntas
```

| Qué | Dónde | Tamaño típico | Por defecto |
|---|---|---|---|
| Salidas de compilación (APK, estado de Gradle/Kotlin) | `android/app/build`, `android/build`, `android/.gradle`, `android/.kotlin` | ~200 MB | Borrar (antes ofrece copiar el APK release a `~/Downloads`) |
| Caché de dependencias de Gradle y Android | `~/.gradle` | 1 GB o más | Conservar: la comparten otros proyectos Android |
| Cachés de módulos y compilación de Go | `go env GOMODCACHE`, `go env GOCACHE` | ~400 MB | Conservar: la comparten otros proyectos Go |
| Gradle de Homebrew | `$(brew --prefix)/Cellar/gradle` | ~230 MB | Conservar (opcional; el proyecto compila con `android/gradlew`) |

El script nunca toca el agente instalado (`~/.local/bin/sublime-sync-agent`), sus datos
(`~/Library/Application Support/sublime-sync`), el Llavero, el servidor ni la carpeta
`local/` (ignorada por git) con tu clave de firma.

Todo lo que borra se vuelve a descargar o compilar solo en la siguiente compilación.
