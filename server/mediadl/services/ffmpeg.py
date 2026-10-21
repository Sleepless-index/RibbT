import logging
import os
import shutil
import subprocess
import threading

from ..config import FFMPEG_CONCURRENCY, FFMPEG_PREVIEW_CONCURRENCY, FFMPEG_SLOT_WAIT_SECONDS
from ..errors import MediaError, blocked_media_url
from .net import resolve_media

log = logging.getLogger(__name__)

# The Android app ships its own ffmpeg and tells us where it is through these variables.
FFMPEG = os.environ.get("RIBBT_FFMPEG") or shutil.which("ffmpeg")
FFPROBE = os.environ.get("RIBBT_FFPROBE") or shutil.which("ffprobe")
PROTOCOL_WHITELIST = "https,tls,tcp,crypto"

_slots = threading.Semaphore(FFMPEG_CONCURRENCY)
_preview_slots = threading.Semaphore(FFMPEG_PREVIEW_CONCURRENCY)


def _library_dirs(libs):
    """Every folder the bundled ffmpeg might load shared libraries from.

    An executable started from an app does not search the APK's native library folder, and the
    unpacked zip may keep some libraries in sub-folders, so all of them are put on the loader path.
    """
    dirs = [libs]
    try:
        for current, subdirs, files in os.walk(libs):
            subdirs.sort()
            if current != libs and any(name.endswith(".so") or ".so." in name for name in files):
                dirs.append(current)
    except OSError:
        pass
    native_dir = os.path.dirname(FFMPEG) if FFMPEG else ""
    if native_dir and os.path.isdir(native_dir):
        dirs.append(native_dir)
    return dirs


def child_env():
    """Environment for ffmpeg: the bundled build needs its shared libraries on the loader path."""
    libs = os.environ.get("RIBBT_FFMPEG_LIBS")
    if not libs:
        return None
    env = dict(os.environ)
    paths = _library_dirs(libs)
    if env.get("LD_LIBRARY_PATH"):
        paths.append(env["LD_LIBRARY_PATH"])
    env["LD_LIBRARY_PATH"] = ":".join(paths)
    return env


class Slot:
    def __init__(self, semaphore):
        self._semaphore = semaphore
        self._lock = threading.Lock()
        self._held = False

    def acquire(self, timeout):
        self._held = self._semaphore.acquire(timeout=timeout)
        return self._held

    def release(self):
        with self._lock:
            if not self._held:
                return
            self._held = False
        self._semaphore.release()


def acquire_slot(preview=False):
    if not FFMPEG:
        raise MediaError(
            "ffmpeg is not installed on the server. Install it and restart the app.",
            500,
        )
    marker = os.environ.get("RIBBT_FFMPEG_READY")
    if marker and not os.path.isfile(marker):
        raise MediaError("Setting up the video tools (first start only). Try again in a moment.", 503)
    slot = Slot(_preview_slots if preview else _slots)
    if not slot.acquire(FFMPEG_SLOT_WAIT_SECONDS):
        raise MediaError("Server is busy. Try again shortly.", 503)
    return slot


def input_args(headers):
    remaining = dict(headers)
    for key in ("Accept-Encoding", "Accept", "Connection", "Range"):
        remaining.pop(key, None)
    user_agent = remaining.pop("User-Agent", None) or remaining.pop("user-agent", None)
    args = ["-protocol_whitelist", PROTOCOL_WHITELIST]
    if user_agent:
        args += ["-user_agent", user_agent]
    args += ["-headers", "".join(f"{k}: {v}\r\n" for k, v in remaining.items())]
    return args


def _tls_options():
    ca_file = os.environ.get("RIBBT_CA_FILE")
    return ["-ca_file", ca_file] if ca_file else []


def _input_options(headers, rw_timeout):
    return [
        *_tls_options(),
        "-reconnect", "1",
        "-reconnect_streamed", "1",
        "-reconnect_at_eof", "1",
        "-reconnect_delay_max", "10",
        "-analyzeduration", "10M",
        "-probesize", "10M",
        *input_args(headers),
        "-rw_timeout", str(rw_timeout),
    ]


def spawn(urls, args, rw_timeout, stdout=subprocess.PIPE):
    command = [FFMPEG, "-hide_banner", "-loglevel", "warning", "-nostdin", "-y"]
    for url in urls:
        final_url, headers = resolve_media(url)
        if not final_url:
            raise blocked_media_url()
        command += [*_input_options(headers, rw_timeout), "-i", final_url]
    command += args
    try:
        return subprocess.Popen(command, stdout=stdout, stderr=subprocess.PIPE, env=child_env())
    except OSError as exc:
        raise MediaError(f"Could not start ffmpeg: {exc}", 500) from exc


def spawn_local(paths, args, stdout=subprocess.PIPE):
    """Run ffmpeg on files that are already on disk (no network options needed)."""
    command = [FFMPEG, "-hide_banner", "-loglevel", "warning", "-nostdin", "-y"]
    for path in paths:
        command += ["-i", path]
    command += args
    try:
        return subprocess.Popen(command, stdout=stdout, stderr=subprocess.PIPE, env=child_env())
    except OSError as exc:
        raise MediaError(f"Could not start ffmpeg: {exc}", 500) from exc


def probe_file_bitrate(path):
    """Average bits per second of a local file, from the container or else size / duration."""
    if not FFPROBE:
        return None
    command = [
        FFPROBE, "-v", "error",
        "-show_entries", "format=bit_rate,duration",
        "-of", "default=noprint_wrappers=1",
        path,
    ]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=25, env=child_env())
    except (OSError, subprocess.SubprocessError):
        return None
    if result.returncode != 0:
        return None
    values = {}
    for line in result.stdout.splitlines():
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    try:
        return int(values["bit_rate"])
    except (KeyError, ValueError):
        pass
    try:
        duration = float(values["duration"])
        if duration > 0:
            return int(os.path.getsize(path) * 8 / duration)
    except (KeyError, ValueError):
        pass
    return None


class StderrTail:
    def __init__(self, process, limit=30):
        self._lines = []
        self._limit = limit
        self._process = process
        self._thread = threading.Thread(target=self._drain, daemon=True)
        self._thread.start()

    def _drain(self):
        try:
            for line in iter(self._process.stderr.readline, b""):
                if len(self._lines) < self._limit:
                    self._lines.append(line)
        except (OSError, ValueError):
            pass

    def join(self, timeout=1.0):
        self._thread.join(timeout=timeout)

    def text(self, last=8):
        raw = b"".join(self._lines).decode("utf-8", "replace").strip()
        lines = [line for line in raw.splitlines() if line.strip()]
        if not lines:
            return "ffmpeg produced no diagnostic output."
        return "\n".join(lines[-last:])


def stop(process):
    for stream in (process.stdout, process.stderr):
        if stream is not None:
            try:
                stream.close()
            except (OSError, ValueError):
                pass
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)


def probe_bitrate(url, headers):
    if not FFPROBE:
        return None
    command = [
        FFPROBE, "-v", "error",
        *input_args(headers),
        "-show_entries", "format=bit_rate",
        "-of", "default=noprint_wrappers=1:nokey=1",
        url,
    ]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=25, env=child_env())
    except (OSError, subprocess.SubprocessError):
        return None
    if result.returncode != 0:
        return None
    try:
        return int(result.stdout.strip().splitlines()[0])
    except (ValueError, IndexError):
        return None
