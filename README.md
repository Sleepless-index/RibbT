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
- YouTube playlists and mixes, with subtitles from YouTube and Bilibili
- Download queue with live progress, cancel and retry
- Choose quality, format and where files are saved
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

## Credits

Built on [yt-dlp](https://github.com/yt-dlp/yt-dlp), [FFmpeg](https://ffmpeg.org), [Chaquopy](https://chaquo.com/chaquopy/) and [Flask](https://flask.palletsprojects.com).

## License

[GPL-3.0](LICENSE)
