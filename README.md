# RibbT (Android)

Native Kotlin + Jetpack Compose client for the RibbT Flask server.
The app carries its own server: the same Python package as the PC/Termux version (Chaquopy),
yt-dlp as a library, and an ffmpeg build. Nothing needs installing besides the APK.

Settings -> Server picks where the app connects:
- **Built-in** (default): starts inside the app on 127.0.0.1:8765, kept alive by a notification.
- **Custom address**: Termux or a PC, as before (the web server from the RibbT web repo).

How the pieces fit:
- `app/src/main/python/ribbt_server.py` starts Flask; Gradle copies `server/mediadl` and
  `server/config/settings.json` next to it before every build (`syncServer` task).
  `server/` is a vendored copy of the web repo's `mediadl/` and `config/settings.json`;
  re-copy them here whenever the web server changes.
- `BuiltInServer.kt` is the foreground service. ffmpeg/ffprobe ship as `libffmpeg.so` /
  `libffprobe.so` (executables in the native lib dir) plus a zip of their libraries that is
  unpacked once on first launch.
- yt-dlp ships in the APK; Settings -> Server -> Update downloads the newest wheel from PyPI
  into the app's storage and uses it from then on (no pip needed).
- Limits: arm64 phones only; the APK is large (about 80 MB); there is no JavaScript runtime in
  the built-in server, so a few YouTube formats may be missing (use a Custom address for those).
- ffmpeg comes from Termux's build (GPL). Keep that in mind if you ever distribute the APK.

## Build
Push to GitHub. Actions builds the APK (Actions tab -> latest run -> Artifacts,
or the "latest" release). Install it over the old one; the committed keystore
keeps updates in place.

## Updating
Server-side features (settings, platforms, parsing): edit and restart the server.
UI changes: edit `app/src/main/java/app/mediadl/*.kt`, push, install new APK.
