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

YouTube, TikTok, X (Twitter), Reddit, Pinterest, Bilibili, RedNote, SoundCloud, Spotify (tracks), Safebooru, Danbooru, Gelbooru, Yande.re, Imgur and Pixiv.

## Install

Download [`RibbT.apk`](https://github.com/Sleepless-index/RibbT/releases/download/latest/RibbT.apk) and open it. Requires Android 10 or newer on an arm64 device.

## Custom server

RibbT runs its downloader inside the app. To use another device instead (Termux on this phone, or a PC on the same Wi-Fi), start the server there and choose Settings → Server → Custom.

Termux (a PC is the same, with its own package manager):

```
pkg install python ffmpeg git
git clone https://github.com/Sleepless-index/RibbT
cd RibbT/server
pip install "flask>=3.0" "requests>=2.31" yt-dlp yt-dlp-ejs certifi websockets mutagen defusedxml
RIBBT_TOKEN="a-long-random-string" python -m flask --app mediadl run --host 0.0.0.0 --port 8000
```

Then, in the app, enter the server address and the same string as the Access token: `http://127.0.0.1:8000` for Termux on the same phone, or `http://<the PC's address>:8000` for a PC. Keep Termux open while you download.

The token is what keeps other apps and other devices on the network from using your server, so always set one when you use `--host 0.0.0.0`. For Termux on the same phone you can listen on `127.0.0.1` instead, but a token still stops other apps on the phone from using it.

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

## Release signing

CI signs the APK with a key kept in the repository's secrets: `RIBBT_KEYSTORE_BASE64` (the keystore, base64), `RIBBT_KEYSTORE_PASSWORD`, and optionally `RIBBT_KEY_ALIAS` (default `ribbt`) and `RIBBT_KEY_PASSWORD`. Without them the build stops. A local `gradle assembleRelease` gives an unsigned APK unless you export `RIBBT_KEYSTORE_FILE`, `RIBBT_KEYSTORE_PASSWORD` and the others yourself.

An app can only be updated by an APK signed with the same key, so keep a private backup of the keystore and its password.

## Credits

Built on [yt-dlp](https://github.com/yt-dlp/yt-dlp), [FFmpeg](https://ffmpeg.org), [Chaquopy](https://chaquo.com/chaquopy/) and [Flask](https://flask.palletsprojects.com).

## License

[GPL-3.0](LICENSE)
