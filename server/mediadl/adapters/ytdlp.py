import json
import re
import subprocess

from ..services import cookies as cookie_store
from ..services import ytdlp_runner
from ..services.candidates import candidate, codec_from_ytdlp

YTDLP_TIMEOUT_SECONDS = 90
COOKIES_ENV = "YTDLP_COOKIES"
DIRECT_PROTOCOLS = (None, "", "https", "http")
MAX_CANDIDATES = 16
PLAYLIST_TIMEOUT_SECONDS = 120
MAX_PLAYLIST_ENTRIES = 100
MAX_SUBTITLE_TRACKS = 30
TITLE_LIMIT = 80
UNAVAILABLE_TITLES = ("[Private video]", "[Deleted video]")


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


def _launch(runner, command, label, timeout_seconds):
    cookies = cookie_store.active_path()
    if cookies:
        command = [*command[:-1], "--cookies", cookies, command[-1]]
    try:
        result = runner(command, timeout_seconds)
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
    return result


def dump_info(url, label, extra_args=()):
    command = ["--dump-json", "--no-download", "--no-warnings", *extra_args, url]
    result = _launch(ytdlp_runner.run, command, label, YTDLP_TIMEOUT_SECONDS)
    try:
        return json.loads(result.stdout.strip().splitlines()[0])
    except (json.JSONDecodeError, IndexError):
        raise ValueError(f"Could not parse the {label} response.")


def dump_infos(query, label, extra_args=()):
    """Every result of a search query, best match first.

    The whole result list is requested as one document: the embedded runner would otherwise
    hand back only the first entry.
    """
    command = ["--dump-single-json", "--no-download", "--no-warnings", *extra_args, query]
    result = _launch(ytdlp_runner.run_playlist, command, label, PLAYLIST_TIMEOUT_SECONDS)
    try:
        document = json.loads(result.stdout.strip().splitlines()[0])
    except (json.JSONDecodeError, IndexError):
        raise ValueError(f"Could not parse the {label} response.")
    entries = document.get("entries") if isinstance(document, dict) else None
    infos = [entry for entry in (entries if entries is not None else [document]) if isinstance(entry, dict)]
    if not infos:
        raise ValueError(f"Could not parse the {label} response.")
    return infos


def dump_playlist(url, label, limit=MAX_PLAYLIST_ENTRIES):
    command = [
        "--flat-playlist", "--dump-single-json", "--yes-playlist", "--no-warnings",
        "--playlist-end", str(limit), url,
    ]
    result = _launch(ytdlp_runner.run_playlist, command, label, PLAYLIST_TIMEOUT_SECONDS)
    try:
        info = json.loads(result.stdout.strip().splitlines()[0])
    except (json.JSONDecodeError, IndexError):
        raise ValueError(f"Could not parse the {label} response.")
    if not isinstance(info, dict):
        raise ValueError(f"Could not parse the {label} response.")
    return info


def clock_text(seconds):
    try:
        total = int(seconds)
    except (TypeError, ValueError):
        return ""
    if total <= 0:
        return ""
    hours, rest = divmod(total, 3600)
    minutes, secs = divmod(rest, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{secs:02d}"
    return f"{minutes}:{secs:02d}"


def _entry_url(entry, watch_base):
    link = entry.get("webpage_url") or entry.get("url") or ""
    if isinstance(link, str) and link.startswith("http"):
        return link
    entry_id = entry.get("id")
    if watch_base and entry_id:
        return watch_base + str(entry_id)
    return ""


def _entry_thumb(entry, thumb_base):
    entry_id = entry.get("id")
    if thumb_base and entry_id:
        return thumb_base.format(id=entry_id)
    thumbnails = [t for t in (entry.get("thumbnails") or []) if isinstance(t, dict) and t.get("url")]
    return thumbnails[-1]["url"] if thumbnails else (entry.get("thumbnail") or "")


def playlist_payload(info, watch_base=None, thumb_base=None):
    entries = []
    for entry in info.get("entries") or []:
        if not isinstance(entry, dict):
            continue
        title = (entry.get("title") or "").strip()
        if title in UNAVAILABLE_TITLES:
            continue
        link = _entry_url(entry, watch_base)
        if not link:
            continue
        entries.append({
            "id": str(entry.get("id") or ""),
            "title": title[:TITLE_LIMIT] or "Untitled",
            "author": entry.get("uploader") or entry.get("channel") or "",
            "duration": clock_text(entry.get("duration")),
            "url": link,
            "thumb": _entry_thumb(entry, thumb_base),
        })
    total = info.get("playlist_count")
    return {
        "id": str(info.get("id") or ""),
        "title": (info.get("title") or "Playlist")[:TITLE_LIMIT],
        "author": info.get("uploader") or info.get("channel") or "",
        "total": total if isinstance(total, int) and total > 0 else len(entries),
        "entries": entries,
    }


def _track_label(lang, formats, auto):
    name = ""
    for item in formats or []:
        if isinstance(item, dict) and item.get("name"):
            name = str(item["name"])
            break
    name = re.sub(r"\s*\((?:Original|original)\)$", "", name).strip() or lang
    return f"{name} (auto)" if auto else name


def subtitle_tracks(info):
    tracks = []
    manual = info.get("subtitles") or {}
    for lang, formats in manual.items():
        if lang == "live_chat" or not isinstance(lang, str):
            continue
        tracks.append({"lang": lang, "label": _track_label(lang, formats, False), "auto": False})
    automatic = info.get("automatic_captions") or {}
    originals = [lang for lang in automatic if isinstance(lang, str) and lang.endswith("-orig")]
    spoken = info.get("language")
    if not originals and spoken in automatic:
        originals = [spoken]
    taken = {track["lang"] for track in tracks}
    for lang in originals:
        base = lang[:-5] if lang.endswith("-orig") else lang
        if lang in taken or base in taken:
            continue
        tracks.append({"lang": lang, "label": _track_label(lang, automatic[lang], True), "auto": True})
    return tracks[:MAX_SUBTITLE_TRACKS]


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
            size=f.get("filesize") or f.get("filesize_approx"),
        )
        if audio_url and not has_stream(f.get("acodec")):
            entry["audio"] = audio_url
        candidates.append(entry)
    return candidates
