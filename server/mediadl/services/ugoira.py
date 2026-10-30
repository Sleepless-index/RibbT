"""Pixiv ugoira: an animation stored as a zip of still frames plus a delay for each frame.

The zip alone has no timing, so the delays come from Pixiv's ugoira_meta. The frames are unpacked into a
private folder, listed for ffmpeg's concat reader with their own durations, and encoded into a video.
"""
import logging
import os
import re
import shutil
import subprocess
import tempfile
import zipfile
from functools import lru_cache
from urllib.parse import urlparse

from ..adapters.pixiv import api
from ..errors import MediaError
from . import ffmpeg, storage
from .delivery import encode_local, fetch_sources
from .net import host_ok
from .transforms import ugoira_transform

log = logging.getLogger(__name__)

ZIP_RE = re.compile(r"/(\d+)_ugoira\d+x\d+\.zip$")
FRAME_EXTENSIONS = {".jpg", ".jpeg", ".png"}
MAX_FRAMES = 1200
MAX_FRAME_BYTES = 30 * 1024 * 1024
MAX_TOTAL_BYTES = 600 * 1024 * 1024


def is_ugoira_url(url):
    return host_ok(url, ("pximg.net",)) and bool(ZIP_RE.search(urlparse(url).path))


@lru_cache(maxsize=1)
def _timing_flag():
    """Keeps each frame's own delay. The option was renamed in ffmpeg 5.1."""
    try:
        text = subprocess.run(
            [ffmpeg.FFMPEG, "-version"], capture_output=True, text=True, timeout=10, env=ffmpeg.child_env(),
        ).stdout
        match = re.search(r"version\D*(\d+)\.(\d+)", text)
        if match and (int(match.group(1)), int(match.group(2))) >= (5, 1):
            return ("-fps_mode", "vfr")
    except (OSError, subprocess.SubprocessError, TypeError):
        pass
    return ("-vsync", "vfr")


def _frames(illust_id):
    meta = api(f"/illust/{illust_id}/ugoira_meta")
    frames = [f for f in ((meta or {}).get("frames") or []) if isinstance(f, dict) and f.get("file")]
    if not frames:
        raise MediaError("Pixiv didn't return the frame timings for this animation. Try again in a moment.", 502)
    if len(frames) > MAX_FRAMES:
        raise MediaError("That animation has too many frames to convert here.", 413)
    return frames


def _unpack(zip_path, frames, folder):
    """Copy the listed frames out of the zip under plain numbered names. Returns [(file name, seconds)]."""
    ordered = []
    total = 0
    with zipfile.ZipFile(zip_path) as archive:
        for index, frame in enumerate(frames):
            source = str(frame["file"])
            extension = os.path.splitext(source)[1].lower()
            if extension not in FRAME_EXTENSIONS or "/" in source or "\\" in source or source.startswith("."):
                raise MediaError("That animation has frames in a format this server can't read.", 502)
            try:
                info = archive.getinfo(source)
            except KeyError:
                raise MediaError("That animation's frame list doesn't match its file. Try again.", 502) from None
            total += info.file_size
            if info.file_size > MAX_FRAME_BYTES or total > MAX_TOTAL_BYTES:
                raise MediaError("That animation is too large to convert here.", 413)
            name = f"f{index:05d}{extension}"
            with archive.open(info) as src, open(os.path.join(folder, name), "wb") as dst:
                shutil.copyfileobj(src, dst)
            ordered.append((name, max(int(frame.get("delay") or 0), 10) / 1000))
    return ordered


def _concat_list(path, ordered):
    lines = ["ffconcat version 1.0"]
    for name, seconds in ordered:
        lines += [f"file '{name}'", f"duration {seconds:.3f}"]
    lines.append(f"file '{ordered[-1][0]}'")  # the last frame is repeated so its own duration counts
    with open(path, "w", encoding="utf-8") as out:
        out.write("\n".join(lines) + "\n")


def convert(url, name, container):
    """Download the frame zip and send it back as one video. Raises MediaError with a plain message."""
    if not ffmpeg.FFMPEG:
        raise MediaError("ffmpeg is not installed on the server. Install it and restart the app.", 500)
    illust_id = ZIP_RE.search(urlparse(url).path).group(1)
    frames = _frames(illust_id)

    zip_path = fetch_sources([url])[0]
    folder = tempfile.mkdtemp(prefix=storage.TEMP_PREFIX, dir=storage.work_dir())
    list_path = os.path.join(folder, "frames.ffconcat")
    try:
        storage.require_space(os.path.getsize(zip_path) * 3)
        try:
            ordered = _unpack(zip_path, frames, folder)
        except zipfile.BadZipFile:
            raise MediaError("Pixiv sent a damaged animation file. Try again.", 502) from None
        finally:
            try:
                os.unlink(zip_path)
            except OSError:
                pass
        _concat_list(list_path, ordered)
        return encode_local(
            [list_path], name, ugoira_transform(container, _timing_flag()),
            total_seconds=sum(seconds for _, seconds in ordered),
        )
    finally:
        shutil.rmtree(folder, ignore_errors=True)
