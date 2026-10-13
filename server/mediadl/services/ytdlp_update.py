"""Check and update the yt-dlp that the adapters call.

On a normal server every adapter runs the ``yt-dlp`` command, so a pip upgrade takes effect on the
next parse without restarting. Inside the Android app (``RIBBT_EMBEDDED=1``) there is no pip: the
newest pure-Python wheel is downloaded from PyPI into ``RIBBT_YTDLP_DIR`` and put first on
``sys.path``.
"""
import io
import importlib
import json
import os
import shutil
import subprocess
import sys
import threading
import zipfile
from pathlib import Path

import requests

from . import ytdlp_runner

CHANNELS = ("stable", "nightly")
VERSION_TIMEOUT_SECONDS = 20
UPDATE_TIMEOUT_SECONDS = 300

_lock = threading.Lock()


class UpdateError(Exception):
    pass


PYPI_URL = "https://pypi.org/pypi/yt-dlp/json"
KEEP_VERSIONS = 2


def _store():
    path = os.environ.get("RIBBT_YTDLP_DIR")
    return Path(path) if path else None


def activate():
    """Embedded mode: put the most recently downloaded yt-dlp ahead of the bundled one."""
    store = _store()
    if not store:
        return None
    marker = store / "current.txt"
    try:
        version = marker.read_text().strip()
    except OSError:
        return None
    target = store / version
    if not (target / "yt_dlp").is_dir():
        return None
    path = str(target)
    if path in sys.path:
        sys.path.remove(path)
    sys.path.insert(0, path)
    _purge_modules()
    return version


def _unpatch_urllib3():
    """yt-dlp wraps a urllib3 regex on import; importing it again would wrap the wrapper and break requests."""
    try:
        import urllib3.util.url as url_module
    except ImportError:
        return
    current = getattr(url_module, "_PERCENT_RE", None)
    while type(current).__name__ == "Urllib3PercentREOverride":
        current = current.re
    if current is not None:
        url_module._PERCENT_RE = current


def _purge_modules():
    _unpatch_urllib3()
    for name in [m for m in sys.modules if m == "yt_dlp" or m.startswith("yt_dlp.")]:
        del sys.modules[name]
    importlib.invalidate_caches()


def _module_version():
    try:
        from yt_dlp import version as v  # noqa: WPS433
        return v.__version__
    except ImportError:
        return None


def _pick_wheel(data, channel):
    """Newest release of the channel with a pure-Python wheel: (version, url)."""
    best = None
    for version, files in (data.get("releases") or {}).items():
        wheels = [
            f for f in files
            if f.get("filename", "").endswith("py3-none-any.whl") and not f.get("yanked")
        ]
        if not wheels:
            continue
        pre = any(ch.isalpha() for ch in version)
        if channel != "nightly" and pre:
            continue
        stamp = wheels[0].get("upload_time_iso_8601") or ""
        if best is None or stamp > best[0]:
            best = (stamp, version, wheels[0]["url"])
    if not best:
        raise UpdateError("PyPI has no yt-dlp release for this channel.")
    return best[1], best[2]


def _embedded_update(channel):
    store = _store()
    if not store:
        raise UpdateError("This build has nowhere to keep updates.")
    try:
        meta = requests.get(PYPI_URL, timeout=30)
        meta.raise_for_status()
        version, url = _pick_wheel(meta.json(), channel)
        before = _module_version()
        if before == version:
            return {"before": before, "after": version, "updated": False, "channel": channel}
        wheel = requests.get(url, timeout=120)
        wheel.raise_for_status()
    except (requests.RequestException, ValueError) as error:
        raise UpdateError(f"Couldn’t reach PyPI: {error.__class__.__name__}. Check your connection.")

    target = store / version
    partial = store / (version + ".partial")
    shutil.rmtree(partial, ignore_errors=True)
    try:
        with zipfile.ZipFile(io.BytesIO(wheel.content)) as archive:
            for name in archive.namelist():
                if name.startswith("yt_dlp/") and ".." not in name.split("/"):
                    archive.extract(name, partial)
        if not (partial / "yt_dlp" / "version.py").is_file():
            raise UpdateError("The downloaded yt-dlp looks incomplete.")
    except (zipfile.BadZipFile, OSError) as error:
        shutil.rmtree(partial, ignore_errors=True)
        raise UpdateError(f"Couldn’t unpack the update: {error}")
    shutil.rmtree(target, ignore_errors=True)
    partial.rename(target)
    (store / "current.txt").write_text(version)
    activate()
    for old in sorted((p for p in store.iterdir() if p.is_dir()), key=lambda p: p.stat().st_mtime)[:-KEEP_VERSIONS]:
        shutil.rmtree(old, ignore_errors=True)
    after = _module_version()
    return {"before": before, "after": after, "updated": after != before, "channel": channel}


def installed_version():
    """Return the installed yt-dlp version, or None when it isn't installed."""
    if ytdlp_runner.embedded():
        return _module_version()
    executable = shutil.which("yt-dlp")
    if not executable:
        return None
    try:
        result = subprocess.run(
            [executable, "--version"], capture_output=True, text=True, timeout=VERSION_TIMEOUT_SECONDS
        )
    except (OSError, subprocess.SubprocessError):
        return None
    version = (result.stdout or "").strip()
    return version if result.returncode == 0 and version else None


def _pip_command(channel, break_system_packages=False):
    command = [sys.executable, "-m", "pip", "install", "--upgrade", "--disable-pip-version-check"]
    if break_system_packages:
        command.append("--break-system-packages")
    if channel == "nightly":
        command += ["--pre", "yt-dlp[default]"]
    else:
        command.append("yt-dlp[default]")
    return command


def _run_pip(channel, break_system_packages=False):
    try:
        return subprocess.run(
            _pip_command(channel, break_system_packages),
            capture_output=True, text=True, timeout=UPDATE_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        raise UpdateError("The update took too long. Check your connection and try again.")
    except OSError as error:
        raise UpdateError(f"Couldn’t run pip: {error}")


def _tail(text):
    lines = [line.strip() for line in (text or "").splitlines() if line.strip()]
    return lines[-1][:240] if lines else ""


def update(channel="stable"):
    """Upgrade yt-dlp. Returns {before, after, updated, channel}."""
    if channel not in CHANNELS:
        channel = "stable"
    if not _lock.acquire(blocking=False):
        raise UpdateError("An update is already running.")
    try:
        if ytdlp_runner.embedded():
            return _embedded_update(channel)
        before = installed_version()
        result = _run_pip(channel)
        if result.returncode != 0 and "externally-managed" in (result.stderr or "").lower():
            result = _run_pip(channel, break_system_packages=True)
        if result.returncode != 0:
            detail = _tail(result.stderr) or _tail(result.stdout)
            raise UpdateError(detail or "pip couldn’t update yt-dlp.")
        after = installed_version()
        return {"before": before, "after": after, "updated": bool(after and after != before), "channel": channel}
    finally:
        _lock.release()
