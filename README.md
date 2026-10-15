# RibbT (Android)

Native Kotlin + Jetpack Compose client for the RibbT Flask server.
The app carries its own server: the same Python package as the PC/Termux version (Chaquopy),
yt-dlp as a library, and an ffmpeg build. Nothing needs installing besides the APK.

Settings -> Server picks where the app connects:
- **Built-in** (default): starts inside the app on 127.0.0.1 (a free port picked at every start), kept alive by a notification.
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
- Settings -> Downloading picks where files go (internal storage, SD card or a custom folder, always inside what
  MediaStore allows: `Download/RibbT`). If the SD card or folder is gone, the download falls back to internal
  storage and the toast says so. "Sort into folders by type" toggles the Videos / Images / Audio subfolders.
- Downloads run as background jobs on the server (`services/jobs.py`): the app polls for progress,
  then copies the finished file with Range-resume. Unfinished jobs are remembered so the app can
  pick them up after a restart. Temp files live in one folder that is cleaned at every start, and
  downloads check free space first.
- Settings -> Server -> yt-dlp (gear icon) -> YouTube sign-in imports a `cookies.txt` for YouTube's age and bot checks
  (only cookies for supported sites are kept; stored privately, never sent back to the app).
- The yt-dlp update checks PyPI's published SHA-256 before installing.
- Server tests: `cd server && python -m unittest discover -s tests -v`.
- Limits: arm64 phones only; the APK is large (about 80 MB); the built-in server
  solves YouTube's challenges with QuickJS (built in CI as `libqjs.so`, plus the `yt-dlp-ejs`
  package), which is slower than Deno or Node; use a Custom address if a format is still missing.
- ffmpeg comes from Termux's build (GPL). Keep that in mind if you ever distribute the APK.

## Build
Push to GitHub. Actions builds the APK (Actions tab -> latest run -> Artifacts,
or the "latest" release). Install it over the old one; the committed keystore
keeps updates in place.

## Updating
Server-side features (settings, platforms, parsing): edit and restart the server.
UI changes: edit `app/src/main/java/app/mediadl/*.kt`, push, install new APK.
