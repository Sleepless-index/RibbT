<div align="center">

<img src="assets/icon.png" alt="RibbT" width="128" height="128">

# RibbT

**Download videos, photos and audio from your favourite sites. Everything runs on your phone.**

[![Build](https://github.com/Sleepless-index/RibbT/actions/workflows/android.yml/badge.svg)](https://github.com/Sleepless-index/RibbT/actions/workflows/android.yml)
[![License: GPL v3](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/Sleepless-index/RibbT?label=release&color=ff2442)](https://github.com/Sleepless-index/RibbT/releases/tag/latest)
![Android 10+](https://img.shields.io/badge/Android-10%2B-3ddc84)

[**Download the APK**](https://github.com/Sleepless-index/RibbT/releases/download/latest/RibbT.apk)

</div>

## Features

- **Paste a link, get the media.** Videos, photo posts, thumbnails and audio, with a preview before you save.
- **Supported sites:** YouTube, TikTok, X (Twitter), Reddit, Pinterest, Bilibili and RedNote.
- **Playlists and mixes:** pick the videos you want from a YouTube playlist or mix and save them as video or audio.
- **Subtitles:** save captions from YouTube and Bilibili as `.srt` files.
- **RedNote Live Photos** are saved as video.
- **Download queue:** every download of the session in one place, with progress, Cancel, Retry and a red dot when something finishes or fails.
- **Self-contained:** the downloader (Python, yt-dlp and ffmpeg) is bundled inside the app. Nothing else to install.
- **Updates itself:** check for a new RibbT or a new yt-dlp from Settings. No background checks, no notifications.
- **Your folders:** save to internal storage, an SD card or a folder you pick, optionally sorted into Videos, Images and Audio.
- **Cookies support** for age and bot checks on YouTube (import a `cookies.txt`; stored privately on the phone).
- **Customisable:** accent colour, layout, filename style and a debug-info option that adds a Copy button to failed downloads.
- No ads, no accounts, no tracking.

## Install

1. Download [`RibbT.apk`](https://github.com/Sleepless-index/RibbT/releases/download/latest/RibbT.apk) from the latest release.
2. Open it and allow installs from your browser or file manager when Android asks.
3. Later updates can be installed from inside the app: **Settings → Server → RibbT → Check**.

Requirements: Android 10 or newer on an arm64 phone. The APK is about 80 MB because it carries Python, yt-dlp and ffmpeg.

Every release lists what changed since the previous build.

## Server modes

**Settings → Server** picks where the app connects:

- **Built-in** (default): the downloader starts inside the app on `127.0.0.1` and is kept alive by a notification.
- **Custom address:** use the same server running elsewhere, such as Termux or a PC.

## Building

The APK is built by GitHub Actions on every push (`.github/workflows/android.yml`), and the `latest` release is replaced with each build. Releases are signed with the committed keystore, so new builds install over old ones.

Locally you need JDK 17, Gradle 8.9 and the Android SDK:

```
gradle assembleRelease
```

CI also builds QuickJS and fetches the ffmpeg support libraries, so a local build without those steps will not include them.

Run the server tests with:

```
cd server && python -m unittest discover -s tests -v
```

### How it fits together

- `app/`: the Kotlin and Jetpack Compose app.
- `server/mediadl/`: the Flask downloader (site adapters, yt-dlp runner, download jobs). Gradle copies it next to `app/src/main/python/ribbt_server.py` before each build and Chaquopy runs it in-process.
- Downloads run as background jobs on the server. The app polls for progress, then copies the finished file with resume support. Unfinished jobs are remembered across restarts.
- yt-dlp ships in the APK. The in-app updater downloads the newest wheel from PyPI, verifies its SHA-256 and uses it from then on.
- YouTube challenges are solved with QuickJS, which is slower than Deno or Node. Use a custom address if a format is still missing.

## Credits

[yt-dlp](https://github.com/yt-dlp/yt-dlp), [FFmpeg](https://ffmpeg.org) (Termux build), [Chaquopy](https://chaquo.com/chaquopy/), [QuickJS-ng](https://github.com/quickjs-ng/quickjs), [Flask](https://flask.palletsprojects.com), [Coil](https://coil-kt.github.io/coil/) and [Media3](https://developer.android.com/media/media3).

## Disclaimer

RibbT is a tool for saving media you are allowed to keep. Respect the terms of the sites you use and the rights of creators. It is not affiliated with any of the supported platforms.

## License

RibbT is free software, released under the [GNU General Public License v3.0](LICENSE). The bundled FFmpeg build is GPL as well.
