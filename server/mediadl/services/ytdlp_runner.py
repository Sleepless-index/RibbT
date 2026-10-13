"""Run yt-dlp for the adapters.

Normally that is the ``yt-dlp`` command (so a pip upgrade applies at once). Inside the Android app
there is no command line, so the same arguments run through yt-dlp's Python API instead
(``RIBBT_EMBEDDED=1``). Both paths return a ``subprocess.CompletedProcess`` and raise
``FileNotFoundError`` / ``subprocess.TimeoutExpired`` the same way.
"""
import json
import os
import subprocess
import threading


def embedded():
    return os.environ.get("RIBBT_EMBEDDED") == "1"


class _Capture:
    """yt-dlp logger that keeps errors so they can be reported like the CLI's stderr."""

    def __init__(self):
        self.errors = []

    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        pass

    def error(self, msg):
        self.errors.append(str(msg))


def _first_entry(info):
    entries = info.get("entries") if isinstance(info, dict) else None
    if entries is None:
        return info
    for entry in entries:
        if entry:
            return entry
    return None


def _extract(args, outcome):
    try:
        import yt_dlp
    except ImportError:
        outcome["missing"] = True
        return
    capture = _Capture()
    try:
        parsed = yt_dlp.parse_options(list(args))
        opts = dict(parsed.ydl_opts)
        for key in ("forcejson", "dump_single_json", "dumpjson", "print_to_file"):
            opts.pop(key, None)
        opts.update(
            quiet=True, no_warnings=True, logger=capture, skip_download=True,
            noplaylist=opts.get("noplaylist", False), socket_timeout=20,
        )
        urls = parsed.urls
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(urls[0], download=False)
            info = _first_entry(info)
            if not info:
                if capture.errors:
                    outcome.update(code=1, stderr="\n".join(capture.errors))
                    return
                raise yt_dlp.utils.DownloadError("ERROR: Nothing to download")
            outcome["stdout"] = json.dumps(ydl.sanitize_info(info)) + "\n"
    except BaseException as exc:  # yt-dlp raises DownloadError, SystemExit on bad options, etc.
        message = str(exc) or exc.__class__.__name__
        if "ERROR" not in message:
            message = f"ERROR: {message}"
        outcome["stderr"] = "\n".join([*capture.errors, message])
        outcome["code"] = 1


def _run_module(args, timeout):
    outcome = {"stdout": "", "stderr": "", "code": 0}
    worker = threading.Thread(target=_extract, args=(args, outcome), daemon=True)
    worker.start()
    worker.join(timeout)
    if worker.is_alive():
        raise subprocess.TimeoutExpired(["yt-dlp", *args], timeout)
    if outcome.get("missing"):
        raise FileNotFoundError("yt_dlp")
    return subprocess.CompletedProcess(
        ["yt-dlp", *args], outcome["code"], outcome["stdout"], outcome["stderr"]
    )


def run(args, timeout):
    """Run ``yt-dlp <args>`` and return a CompletedProcess with text stdout/stderr."""
    if embedded():
        return _run_module(args, timeout)
    return subprocess.run(["yt-dlp", *args], capture_output=True, text=True, timeout=timeout)
