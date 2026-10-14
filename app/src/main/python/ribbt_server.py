"""Starts the media server inside the Android app (Chaquopy).

Kotlin calls ``start()``; the same Flask app that runs on a PC or in Termux then listens on
127.0.0.1 for the app's own requests.
"""
import logging
import os
import threading

_server = None
_lock = threading.Lock()
log = logging.getLogger("ribbt")


def _write_ca_file(data_dir):
    """ffmpeg's TLS needs a CA bundle file; certifi's text is copied to a real path."""
    try:
        import certifi

        path = os.path.join(data_dir, "cacert.pem")
        text = certifi.contents()
        if not os.path.isfile(path) or os.path.getsize(path) != len(text.encode("utf-8")):
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(text)
        return path
    except Exception:  # noqa: BLE001 - ffmpeg then falls back to its own defaults
        log.exception("could not write the CA bundle")
        return None


def _bind(app, preferred):
    """Listen on ``preferred`` if it is free, otherwise on any free port the system hands out.

    Passing 0 means "any free port", which is what the app does: a fixed port can be taken by another
    app or by a stuck process from a previous run, and then the server could never start.
    """
    from werkzeug.serving import make_server

    attempts = [int(preferred), 0] if preferred else [0]
    last_error = None
    for candidate in attempts:
        try:
            return make_server("127.0.0.1", candidate, app, threaded=True)
        except (OSError, SystemExit) as error:  # werkzeug calls sys.exit(1) when the port is taken
            last_error = error
            log.warning("port %s is not available: %s", candidate, error)
    raise last_error


def start(data_dir, ffmpeg, ffprobe, ffmpeg_libs, ready_marker, port, qjs=None):
    """Start the server once. Returns the port it actually listens on."""
    global _server
    with _lock:
        if _server is not None:
            return int(_server.server_port)
        os.makedirs(data_dir, exist_ok=True)
        env = os.environ
        env["RIBBT_EMBEDDED"] = "1"
        env["MDL_HISTORY_PATH"] = os.path.join(data_dir, "history.json")
        env["RIBBT_YTDLP_DIR"] = os.path.join(data_dir, "ytdlp")
        # Temp files and unfinished downloads live in one folder that is cleaned at every start.
        env["RIBBT_TMP_DIR"] = os.path.join(data_dir, "tmp")
        env["RIBBT_COOKIES_PATH"] = os.path.join(data_dir, "cookies.txt")
        if ffmpeg:
            env["RIBBT_FFMPEG"] = ffmpeg
        if ffprobe:
            env["RIBBT_FFPROBE"] = ffprobe
        if ffmpeg_libs:
            env["RIBBT_FFMPEG_LIBS"] = ffmpeg_libs
        if ready_marker:
            env["RIBBT_FFMPEG_READY"] = ready_marker
        if qjs and os.path.isfile(qjs):
            env["RIBBT_QJS"] = qjs
        ca_file = _write_ca_file(data_dir)
        if ca_file:
            env["RIBBT_CA_FILE"] = ca_file
            env.setdefault("SSL_CERT_FILE", ca_file)

        from mediadl.services import ytdlp_update

        active = ytdlp_update.activate()  # a yt-dlp the user updated to beats the bundled one
        log.info("yt-dlp %s", active or "bundled")

        from mediadl import create_app

        _server = _bind(create_app(), port)
        threading.Thread(target=_server.serve_forever, name="ribbt-http", daemon=True).start()
        return int(_server.server_port)


def stop():
    global _server
    with _lock:
        if _server is not None:
            _server.shutdown()
            _server = None
