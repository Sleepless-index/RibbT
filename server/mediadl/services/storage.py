"""Where the server keeps temporary files, and how it avoids filling the disk with them.

Everything the downloader writes while working (fetched sources, converted output, finished
background downloads) lives in one directory, so a crash can be cleaned up by looking in one place.
"""
import logging
import os
import shutil
import tempfile
import time
from pathlib import Path

from ..errors import MediaError

log = logging.getLogger(__name__)

TEMP_PREFIX = "mdl_"
# Always keep this much free on top of what a download needs, so the phone doesn't run dry.
RESERVE_BYTES = 150 * 1024 * 1024
# On a shared machine only files this old are treated as leftovers; inside the app (a private
# directory, one server process) everything left from the last run is.
STALE_SECONDS = 60 * 60
MAX_TEMP_AGE_SECONDS = 6 * 60 * 60


def embedded():
    return os.environ.get("RIBBT_EMBEDDED") == "1"


def work_dir():
    configured = os.environ.get("RIBBT_TMP_DIR")
    path = Path(configured) if configured else Path(tempfile.gettempdir()) / "ribbt"
    path.mkdir(parents=True, exist_ok=True)
    return path


def jobs_dir():
    path = work_dir() / "jobs"
    path.mkdir(parents=True, exist_ok=True)
    return path


def temp_path(suffix):
    handle, path = tempfile.mkstemp(prefix=TEMP_PREFIX, suffix=suffix, dir=work_dir())
    os.close(handle)
    return path


def free_bytes():
    return shutil.disk_usage(work_dir()).free


def human(count):
    value = float(count)
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value:.0f} {unit}" if unit in ("B", "KB") else f"{value:.1f} {unit}"
        value /= 1024


def require_space(needed, reserve=RESERVE_BYTES):
    """Refuse early, with a plain message, instead of failing half way through a write."""
    try:
        free = free_bytes()
    except OSError:
        return
    needed = max(0, int(needed or 0))
    if free < needed + reserve:
        raise MediaError(
            f"Not enough free storage to download this. It needs about {human(needed + reserve)}, "
            f"but only {human(free)} is free. Free up some space and try again.",
            507,
        )


def sweep(max_age):
    """Delete leftover temp files (not the jobs folder) older than ``max_age`` seconds."""
    removed = 0
    cutoff = time.time() - max_age
    try:
        entries = list(work_dir().iterdir())
    except OSError:
        return 0
    for entry in entries:
        if not entry.name.startswith(TEMP_PREFIX) or not entry.is_file():
            continue
        try:
            if entry.stat().st_mtime <= cutoff:
                entry.unlink()
                removed += 1
        except OSError:
            continue
    return removed


def startup_cleanup():
    removed = sweep(0 if embedded() else STALE_SECONDS)
    if removed:
        log.info("removed %d leftover temp file(s)", removed)
    return removed
