import json
from urllib.parse import quote, unquote

from .config import DEFAULT_SETTINGS_PATH

CHOICES = {
    "appearance.theme": {
        "auto": "Auto",
        "light": "Light",
        "dark": "Dark",
    },
    "display.layout": {
        "carousel": "Carousel",
        "stacked": "Stacked",
        "masonry": "Masonry",
    },
    "video.quality": {
        "best": "Best available",
        "4k": "4K (2160p)",
        "1440": "1440p",
        "1080": "1080p",
        "720": "720p",
        "480": "480p",
        "360": "360p",
        "144": "144p",
    },
    "video.container": {
        "auto": "Auto (MP4)",
        "mp4": "MP4",
        "mkv": "MKV",
    },
    "video.bitrate": {
        "auto": "Auto",
        "2m": "2 Mbps",
        "4m": "4 Mbps",
        "6m": "6 Mbps",
        "8m": "8 Mbps",
        "12m": "12 Mbps",
        "20m": "20 Mbps",
    },
    "images.container": {
        "auto": "Auto (original)",
        "jpg": "JPEG",
        "png": "PNG",
        "webp": "WebP",
    },
    "audio.format": {
        "mp3": "MP3",
        "m4a": "M4A (AAC)",
        "opus": "Opus",
    },
    "audio.bitrate": {
        "128": "128 kbps",
        "192": "192 kbps",
        "256": "256 kbps",
        "320": "320 kbps",
    },
    "save.location": {
        "browser": "Browser downloads",
        "downloads": "Downloads/MediaDL",
        "pictures": "Pictures/MediaDL",
        "movies": "Movies/MediaDL",
    },
    "metadata.filename_style": {
        "classic": "Classic",
        "title": "Title only",
        "author_title": "Author - Title",
    },
}

# Drives the native app's settings screen, so new options need no app update.
# `app: False` rows only make sense in the web UI.
SCHEMA = [
    {"section": "Video", "path": "video.quality", "label": "Preferred quality", "hint": "Highest resolution to download when available.", "type": "choice"},
    {"section": "Video", "path": "video.bitrate", "label": "Bitrate cap", "hint": "Upper limit for video bitrate. Lower values give smaller files.", "type": "choice"},
    {"section": "Video", "path": "video.container", "label": "Container", "hint": "File format for saved videos.", "type": "choice"},
    {"section": "Images", "path": "images.container", "label": "Format", "hint": "File format for saved images.", "type": "choice"},
    {"section": "Audio", "path": "audio.format", "label": "Format", "hint": "File format for extracted audio.", "type": "choice"},
    {"section": "Audio", "path": "audio.bitrate", "label": "Bitrate", "hint": "Encoding quality for extracted audio.", "type": "choice"},
    {"section": "Audio", "path": "audio.tiktok_original", "label": "TikTok original sound", "hint": "Download the post’s original sound track when available.", "type": "toggle"},
    {"section": "X / Twitter", "path": "x.loop_as_gif", "label": "Loop videos as GIF", "hint": "Save looping X videos as animated GIFs.", "type": "toggle"},
    {"section": "Saving", "path": "metadata.filename_style", "label": "Filename style", "hint": "Naming convention applied to saved files.", "type": "choice"},
]

TOGGLES = ("audio.tiktok_original", "x.loop_as_gif", "paste.auto_go")

DEFAULTS = {
    "appearance": {"theme": "auto"},
    "display": {"layout": "carousel"},
    "video": {"quality": "best", "container": "auto", "bitrate": "auto"},
    "images": {"container": "auto"},
    "audio": {"format": "mp3", "bitrate": "192", "tiktok_original": False},
    "x": {"loop_as_gif": False},
    "metadata": {"filename_style": "classic"},
    "save": {"location": "browser"},
    "paste": {"auto_go": True},
}

LEGACY_FILENAME_STYLES = {"basic": "author_title"}


def _split(path):
    section, key = path.split(".")
    return section, key


def _migrate(raw):
    display = raw.get("display")
    if isinstance(display, dict) and display.get("layout") == "columns":
        raw = {**raw, "display": {**display, "layout": "masonry"}}
    metadata = raw.get("metadata")
    if isinstance(metadata, dict) and metadata.get("filename_style") in LEGACY_FILENAME_STYLES:
        metadata = {**metadata, "filename_style": LEGACY_FILENAME_STYLES[metadata["filename_style"]]}
        raw = {**raw, "metadata": metadata}
    return raw


def validate(raw):
    result = json.loads(json.dumps(DEFAULTS))
    if not isinstance(raw, dict):
        return result
    raw = _migrate(raw)

    def submitted(path):
        section, key = _split(path)
        block = raw.get(section)
        return block.get(key) if isinstance(block, dict) else None

    for path, labels in CHOICES.items():
        value = submitted(path)
        if isinstance(value, str) and value in labels:
            section, key = _split(path)
            result[section][key] = value

    for path in TOGGLES:
        value = submitted(path)
        if isinstance(value, bool):
            section, key = _split(path)
            result[section][key] = value

    return result


def load_defaults():
    try:
        with open(DEFAULT_SETTINGS_PATH, "r", encoding="utf-8") as handle:
            raw = json.load(handle)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        raw = {}
    return validate(raw)


def from_cookie(value):
    if not value:
        return None
    try:
        return validate(json.loads(unquote(value)))
    except (ValueError, TypeError):
        return None


def to_cookie(settings):
    return quote(json.dumps(settings, separators=(",", ":")), safe="")


def schema_payload():
    return SCHEMA


def choices_payload():
    return {
        path: [{"value": value, "label": label} for value, label in labels.items()]
        for path, labels in CHOICES.items()
    }
