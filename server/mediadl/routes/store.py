import logging
import shutil
import subprocess

from flask import Blueprint, jsonify, request

from ..config import SAVE_FOLDERS
from ..preferences import CHOICES
from ..services.mediatypes import safe_filename
from .common import current_preferences

log = logging.getLogger(__name__)

bp = Blueprint("store", __name__)

LOOPBACK = {"127.0.0.1", "::1"}


def _error(message, status):
    return jsonify(error=message), status


def _unique(folder, name):
    candidate = folder / name
    stem, dot, ext = name.rpartition(".")
    if not dot:
        stem, ext = name, ""
    count = 1
    while candidate.exists():
        candidate = folder / (f"{stem} ({count}).{ext}" if ext else f"{stem} ({count})")
        count += 1
    return candidate


@bp.post("/api/store")
def store():
    """Write a finished download straight into the chosen folder (same device only)."""
    if request.remote_addr not in LOOPBACK:
        return _error("Saving to a folder only works from the device running the app.", 403)

    location = current_preferences()["save"]["location"]
    folder = SAVE_FOLDERS.get(location)
    if folder is None:
        return _error("Saving to a folder is turned off. Pick one in Settings.", 400)

    # No leading dots: keeps names like ".." or ".bashrc" out of the folder.
    name = safe_filename(request.args.get("name", "media")).lstrip(". ") or "media"

    try:
        if not folder.parent.is_dir():
            return _error(
                "Storage access isn’t set up. Run termux-setup-storage, then try again.", 500
            )
        folder.mkdir(exist_ok=True)
        target = _unique(folder, name)
        partial = target.with_name(target.name + ".part")
        with open(partial, "wb") as handle:
            shutil.copyfileobj(request.stream, handle, 1024 * 1024)
        partial.replace(target)
    except OSError as exc:
        log.exception("store failed")
        return _error(f"Couldn’t write to {CHOICES['save.location'][location]}: {exc.strerror or exc}", 500)

    scanner = shutil.which("termux-media-scan")
    if scanner:
        try:
            subprocess.Popen([scanner, str(target)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError:
            pass

    return jsonify(folder=CHOICES["save.location"][location], name=target.name)
