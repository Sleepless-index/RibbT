import html
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

from ..errors import MediaError
from . import cookies as cookie_store
from . import storage, ytdlp_runner

LANG_RE = re.compile(r"[A-Za-z0-9_-]{1,24}")
TIMEOUT_SECONDS = 120
PREFERRED = ("srt", "vtt")
MIME = {
    "srt": "application/x-subrip",
    "vtt": "text/vtt",
    "ass": "text/x-ssa",
    "ttml": "application/ttml+xml",
}
STAMP = re.compile(r"^(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})$")
TAG = re.compile(r"<[^>]+>")


def _stamp(value):
    match = STAMP.match((value or "").strip())
    if not match:
        return "00:00:00,000"
    hours, minutes, seconds, millis = match.groups()
    return f"{int(hours or 0):02d}:{int(minutes):02d}:{int(seconds):02d},{millis.ljust(3, '0')}"


def vtt_to_srt(text):
    text = text.replace("\r\n", "\n").replace("\r", "\n").lstrip("﻿")
    cues = []
    for block in re.split(r"\n{2,}", text.strip()):
        lines = block.split("\n")
        marker = next((i for i, line in enumerate(lines) if "-->" in line), None)
        if marker is None:
            continue
        start, _, rest = lines[marker].partition("-->")
        parts = rest.strip().split()
        if not parts:
            continue
        body = [html.unescape(TAG.sub("", line)).strip() for line in lines[marker + 1:]]
        body = [line for line in body if line]
        if not body:
            continue
        cues.append(f"{len(cues) + 1}\n{_stamp(start)} --> {_stamp(parts[0])}\n" + "\n".join(body))
    return "\n\n".join(cues) + "\n" if cues else ""


def _pick(folder):
    found = [path for path in Path(folder).iterdir() if path.is_file() and path.stem.startswith("sub")]
    if not found:
        return None
    for ext in PREFERRED:
        for path in found:
            if path.suffix.lower() == "." + ext:
                return path
    return found[0]


def _command(url, lang, auto, folder):
    command = [
        "--skip-download", "--no-playlist", "--no-warnings",
        "--write-auto-subs" if auto else "--write-subs",
        "--sub-langs", lang,
        "--sub-format", "srt/vtt/best",
        "-o", os.path.join(folder, "sub.%(ext)s"),
    ]
    cookies = cookie_store.active_path()
    if cookies:
        command += ["--cookies", cookies]
    command.append(url)
    return command


def fetch(url, lang, auto):
    if not LANG_RE.fullmatch(lang or ""):
        raise MediaError("That subtitle language isn’t available.", 400)
    folder = tempfile.mkdtemp(prefix=storage.TEMP_PREFIX + "sub_", dir=storage.work_dir())
    try:
        try:
            result = ytdlp_runner.run_download(_command(url, lang, auto, folder), TIMEOUT_SECONDS)
        except FileNotFoundError:
            raise MediaError("Subtitles need yt-dlp on the server.", 502)
        except subprocess.TimeoutExpired:
            raise MediaError("Getting the subtitles timed out. Try again.", 504)
        chosen = _pick(folder)
        if result.returncode != 0 and chosen is None:
            lines = [line for line in (result.stderr or "").splitlines() if "ERROR" in line]
            detail = lines[-1].split("ERROR:", 1)[-1].strip()[:160] if lines else ""
            raise MediaError("Couldn’t get those subtitles." + (f" {detail}" if detail else ""), 502)
        if chosen is None:
            raise MediaError("This video has no subtitles in that language.", 404)
        ext = chosen.suffix.lower().lstrip(".")
        content = chosen.read_bytes()
        if ext == "vtt":
            converted = vtt_to_srt(content.decode("utf-8", errors="replace"))
            if converted:
                content, ext = converted.encode("utf-8"), "srt"
        if not content.strip():
            raise MediaError("The subtitle file came back empty.", 502)
        return content, ext
    finally:
        shutil.rmtree(folder, ignore_errors=True)
