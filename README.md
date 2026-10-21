<div align="center">

<img src="assets/icon.png" alt="RibbT" width="128" height="128">

# RibbT

Download videos, photos and audio from social media.

[![License: GPL v3](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
![Android 10+](https://img.shields.io/badge/Android-10%2B-3ddc84)

[**Download the latest APK**](https://github.com/Sleepless-index/RibbT/releases/download/latest/RibbT.apk)

</div>

## Features

- Save videos, photos, thumbnails and audio
- YouTube playlists and mixes
- Save subtitles from YouTube and Bilibili videos
- Download queue with progress, cancel and retry
- Choose where files are saved
- No ads, no accounts, no tracking

## Supported sites

YouTube, TikTok, X (Twitter), Reddit, Pinterest, Bilibili, RedNote, Instagram, SoundCloud and Spotify (tracks).

## Install

Download [`RibbT.apk`](https://github.com/Sleepless-index/RibbT/releases/download/latest/RibbT.apk) and open it. Requires Android 10 or newer on an arm64 device.

## Build

GitHub Actions builds the APK on every push and publishes it to the `latest` release.

To build locally you need JDK 17, Gradle 8.9 and the Android SDK:

```
gradle assembleRelease
```

Server tests:

```
cd server && python -m unittest discover -s tests -v
```

## Credits

Built on [yt-dlp](https://github.com/yt-dlp/yt-dlp), [FFmpeg](https://ffmpeg.org), [Chaquopy](https://chaquo.com/chaquopy/) and [Flask](https://flask.palletsprojects.com).

## License

[GPL-3.0](LICENSE)
