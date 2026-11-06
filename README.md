# tvOS Launcher UHD: Apple TV style home screen for Android TV & Google TV

> 🇹🇷 Türkçe: **[README_TR.md](README_TR.md)** · Original project: **[README_UPSTREAM.md](README_UPSTREAM.md)**

![Android TV home screen in tvOS / Apple TV style with clock and calendar widgets](docs/uhd/hero-home-clock-calendar.jpg)

A fork of [techgenius1/tvOS-Launcher](https://github.com/techgenius1/tvOS-Launcher) (v2.1.2, Apache-2.0) that turns a
cheap Android TV or Google TV box into an Apple-TV-style TV. It keeps everything upstream has (liquid-glass dock,
night scenes, Continue Watching, debloater) and adds a **phone remote with no app to install**, **home-screen widgets**
(clock, calendar), a **video / fireplace screensaver with a clock**, **4K scenes** and a few quality-of-life fixes.
Built and tested on a 1.8 GB RAM MediaTek TV (Android 14), so it stays light.

Keywords: Android TV launcher, Google TV launcher, tvOS launcher, Apple TV style home screen, liquid glass dock,
Android TV screensaver, fireplace screensaver, phone remote control, TV widgets, 4K wallpapers, Mi Box, Chromecast with Google TV.

## What this fork adds

| Feature | Notes |
|---|---|
| **Phone remote (no app to install)** | The TV runs a small web server (port `8765`). Scan the QR code on the TV with the phone camera, same Wi-Fi. D-pad, OK, back/home, volume, play/pause, app shortcuts, a browser tab and a trackpad. |
| **Home-screen widgets** | Analog clock and month calendar widgets that you place on the home screen (see the picture above). |
| **Screensaver (DreamService)** | Looping muted video (`somine.mp4`) or, if no video is present, a procedural animated fireplace, with a large clock and date on top. |
| **4K scenes** | Night Scenes are rendered on a 4K surface, with prefetching and a background loader. |
| **Liquid-glass transition** | Smooth glass blend when moving between home and the control panel. |
| **Home button recovery** | Takes the Home key back from the stock Google TV launcher, plus accessibility helpers. |
| **YouTube seek then autoplay** | Skipping forward/back in YouTube resumes playback automatically. |
| **Continue Watching on by default** | Upstream v2.1.2 feature, enabled out of the box here. |
| **Auto-update dialog off** | The in-app updater no longer pops up; you can still check manually in Settings. |

### Phone remote

| QR code on the TV | The remote on the phone |
|---|---|
| ![QR](docs/uhd/phone-remote-qr.jpg) | <img src="docs/uhd/phone-remote-app.png" width="260"> |

### Wallpapers and widgets

![Wallpapers with clock and calendar widgets](docs/uhd/wallpapers-widgets-1.jpg)
![More wallpapers](docs/uhd/wallpapers-widgets-2.jpg)
![Desert scene with the calendar widget](docs/uhd/home-desert-calendar.jpg)

## Screensaver

Set the launcher as the system screensaver (Settings → Device preferences → Screen saver). It uses, in order:

1. `somine.mp4` in the app's external files folder, played muted in a loop and center-cropped to fill the screen.
2. Otherwise a built-in procedural fireplace (no download needed).

Put your own video on the TV (it is **not** shipped in this repo; Pexels videos are free to use under the
[Pexels license](https://www.pexels.com/license/)):

```bash
adb push fireplace.mp4 /sdcard/Android/data/<your.package>/files/somine.mp4
```

A 1080p H.264 file is the safe choice on low-RAM TVs. To use the old wallpaper-shuffle screensaver instead, set the
`screensaver_style` preference (file `screensaver`) to `scenes`.

## Build

JDK 17+, Android SDK build-tools 34.0.0 and platform android-34. No Gradle.

```bash
./build.sh                                   # -> build/tvos-home.apk (debug-signed)
VERSION_CODE=150 VERSION_NAME=2.1.2-uhd8 PKG=com.example.tvoslauncher ./build.sh
```

`PKG` renames the application id so the build installs next to (or instead of) another build. Updates keep data as
long as the id and signing key stay the same, and `VERSION_CODE` must go up on every build.

## Credits and license

Apache-2.0, same as upstream; see [LICENSE](LICENSE) and [NOTICE](NOTICE). All credit for the launcher itself goes to
[techgenius](https://github.com/techgenius1/tvOS-Launcher) and the projects listed in `NOTICE`. This fork only adds
the features in the table above. Not affiliated with Apple; "tvOS" is a trademark of Apple Inc.
