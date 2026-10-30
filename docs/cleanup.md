# Cleaning up after a build

**English** · [Español](cleanup.es.md)

Building sublime-sync uses disk space in a few places. `scripts/clean.sh` frees it and
asks before each step:

```sh
scripts/clean.sh          # interactive
scripts/clean.sh --yes    # only this project's build outputs, no questions
```

| What | Where | Typical size | Default |
|---|---|---|---|
| Project build outputs (APK, Gradle/Kotlin state) | `android/app/build`, `android/build`, `android/.gradle`, `android/.kotlin` | ~200 MB | Delete (offers to copy the release APK to `~/Downloads` first) |
| Gradle & Android dependency cache | `~/.gradle` | 1 GB+ | Keep: shared with other Android projects |
| Go module and build caches | `go env GOMODCACHE`, `go env GOCACHE` | ~400 MB | Keep: shared with other Go projects |
| Homebrew Gradle | `$(brew --prefix)/Cellar/gradle` | ~230 MB | Keep (optional; the project builds with `android/gradlew`) |

The script never touches the installed agent (`~/.local/bin/sublime-sync-agent`), its
data (`~/Library/Application Support/sublime-sync`), the Keychain, the server, or the
git-ignored `local/` folder with your signing key.

Everything it deletes is downloaded or rebuilt automatically on the next build.
