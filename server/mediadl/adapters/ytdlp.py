import json
import subprocess

from ..services import cookies as cookie_store
from ..services import ytdlp_runner
from ..services.candidates import candidate, codec_from_ytdlp

YTDLP_TIMEOUT_SECONDS = 90
COOKIES_ENV = "YTDLP_COOKIES"
DIRECT_PROTOCOLS = (None, "", "https", "http")
MAX_CANDIDATES = 16


def has_stream(codec):
    return codec not in (None, "none")


def best_audio_url(formats):
    audio_only = [
        f for f in formats
        if f.get("url") and not has_stream(f.get("vcodec")) and has_stream(f.get("acodec"))
        and f.get("protocol") in DIRECT_PROTOCOLS
    ]
    if not audio_only:
        return None
    aac = [f for f in audio_only if (f.get("acodec") or "").startswith("mp4a")]
    best = max(aac or audio_only, key=lambda f: f.get("abr") or f.get("tbr") or 0)
    return best["url"]


def _error_line(stderr):
    lines = [line.strip() for line in (stderr or "").splitlines() if "ERROR" in line]
    if not lines:
        return ""
    text = lines[-1].split("ERROR:", 1)[-1].strip()
    return text[:200]


def dump_info(url, label, extra_args=()):
    """Run yt-dlp --dump-json and return the first JSON object, raising ValueError for the UI."""
    command = ["--dump-json", "--no-download", "--no-warnings", *extra_args]
    cookies = cookie_store.active_path()
    if cookies:
        command += ["--cookies", cookies]
    command.append(url)

    try:
        result = ytdlp_runner.run(command, YTDLP_TIMEOUT_SECONDS)
    except FileNotFoundError:
        raise ValueError(f"{label} support requires yt-dlp. Install it with: pip install yt-dlp")
    except subprocess.TimeoutExpired:
        raise ValueError(f"{label} timed out. Try again.")

    if result.returncode != 0:
        detail = _error_line(result.stderr)
        lowered = detail.lower()
        if "sign in" in lowered or "bot" in lowered or "cookies" in lowered:
            if ytdlp_runner.embedded():
                fix = "Import your cookies.txt in Settings → Server → Sign-in cookies."
            else:
                fix = f"Import a cookies.txt (POST /api/cookies) or set {COOKIES_ENV} to one on the server."
            raise ValueError(f"{label} wants a sign-in or bot check for this video. {fix}")
        raise ValueError(f"Could not parse that {label} link." + (f" ({detail})" if detail else ""))
    try:
        return json.loads(result.stdout.strip().splitlines()[0])
    except (json.JSONDecodeError, IndexError):
        raise ValueError(f"Could not parse the {label} response.")


def video_candidates(formats, audio_url):
    """Direct-download video candidates, best per (height, codec), with audio attached when needed."""
    best = {}
    for f in formats:
        if not f.get("url") or not has_stream(f.get("vcodec")):
            continue
        if f.get("protocol") not in DIRECT_PROTOCOLS:
            continue
        codec = codec_from_ytdlp(f.get("vcodec"))
        key = (f.get("height") or 0, codec, has_stream(f.get("acodec")))
        if key not in best or (f.get("tbr") or 0) > (best[key].get("tbr") or 0):
            best[key] = f

    ordered = sorted(
        best.values(),
        key=lambda f: (f.get("height") or 0, f.get("tbr") or 0),
        reverse=True,
    )[:MAX_CANDIDATES]

    candidates = []
    for f in ordered:
        entry = candidate(
            f["url"],
            src=f.get("format_note") or None,
            codec=codec_from_ytdlp(f.get("vcodec")),
            height=f.get("height"),
        )
        if audio_url and not has_stream(f.get("acodec")):
            entry["audio"] = audio_url
        candidates.append(entry)
    return candidates
