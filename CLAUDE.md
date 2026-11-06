# CLAUDE.md (public)

Fork of techgenius1/tvOS-Launcher v2.1.2, plain Java, no Gradle, no third-party runtime deps.

- Source: `src/com/techgenius/tvoslauncher/`. Resources in `res/`, assets in `assets/`.
- Build: `./build.sh` (JDK 17+, build-tools 34.0.0, android-34). Env: `VERSION_CODE`, `VERSION_NAME`, `PKG`.
  Bump `VERSION_CODE` on every build or the install is rejected as a downgrade.
- Fork additions: `RemoteServer` (phone remote, port 8765, `assets/remote.html`), `Widgets`, `ScreensaverService`,
  `FireplaceView`, `VideoFireView`, 4K work in `Scenes`/`SceneHome`, Home-key work in `PowerService`.
- Keep upstream files mergeable: prefer new classes over big edits in `SceneHome`, `Scenes`, `PowerService`, `MainActivity`.
- Never commit keystores, APKs, videos, device IPs or personal data. `.gitignore` covers keystores and `build/`.
- Test on a TV with adb. The screensaver cannot be started with `cmd dreams` on user builds; lower
  `settings put system screen_off_timeout 15000`, wait, then restore it.
