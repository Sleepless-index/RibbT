import os
from dataclasses import dataclass
from pathlib import Path

PACKAGE_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = PACKAGE_DIR.parent

# The Android app ships its defaults inside the package; a normal checkout keeps them in config/.
_BUNDLED_SETTINGS = PACKAGE_DIR / "defaults" / "settings.json"
DEFAULT_SETTINGS_PATH = Path(
    os.environ.get(
        "MDL_SETTINGS_PATH",
        _BUNDLED_SETTINGS if _BUNDLED_SETTINGS.is_file() else PROJECT_ROOT / "config" / "settings.json",
    )
)

# "Save to" folders (Termux shared storage; run `termux-setup-storage` once).
SAVE_FOLDERS = {
    "downloads": Path.home() / "storage" / "downloads" / "MediaDL",
    "pictures": Path.home() / "storage" / "pictures" / "MediaDL",
    "movies": Path.home() / "storage" / "movies" / "MediaDL",
}

HISTORY_PATH = Path(os.environ.get("MDL_HISTORY_PATH", PROJECT_ROOT / "config" / "history.json"))
HISTORY_LIMIT = 200

SETTINGS_COOKIE = "mdl_settings"
SETTINGS_COOKIE_MAX_AGE = 60 * 60 * 24 * 365

FFMPEG_CONCURRENCY = 4
FFMPEG_PREVIEW_CONCURRENCY = 3
FFMPEG_SLOT_WAIT_SECONDS = 2
TRANSCODE_TIMEOUT_SECONDS = 600
STREAM_CHUNK_BYTES = 65536

MEDIA_CONNECT_TIMEOUT_SECONDS = 10
MAX_REDIRECTS = 4
PAGE_MAX_REDIRECTS = 8
REDIRECT_CODES = (301, 302, 303, 307, 308)

MOBILE_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
)
DESKTOP_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

PAGE_HEADERS = {
    "User-Agent": MOBILE_UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "Referer": "https://www.xiaohongshu.com/",
}
DESKTOP_PAGE_HEADERS = {
    **PAGE_HEADERS,
    "User-Agent": DESKTOP_UA,
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
}

MEDIA_BASE_HEADERS = {
    "User-Agent": DESKTOP_UA,
    "Accept": "*/*",
    "Accept-Language": "en-US,en;q=0.9",
    "Accept-Encoding": "identity",
}


@dataclass(frozen=True)
class MediaSource:
    hosts: tuple
    referers: tuple = ("",)


MEDIA_SOURCES = (
    MediaSource(
        hosts=("twimg.com",),
        referers=("", "https://x.com/", "https://twitter.com/"),
    ),
    MediaSource(
        hosts=(
            "bilivideo.com",
            "bilivideo.cn",
            "hdslb.com",
            "upos-hz-mirrorakam.akamaized.net",
            "upos-sz-mirrorakam.akamaized.net",
        ),
        referers=("https://www.bilibili.com/",),
    ),
    MediaSource(
        hosts=(
            "tiktokcdn.com",
            "tiktokcdn-us.com",
            "tiktokv.com",
            "tiktokv.us",
            "byteoversea.com",
            "ibytedtos.com",
        ),
        referers=("https://www.tiktok.com/",),
    ),
    MediaSource(hosts=("tikwm.com",)),
    MediaSource(
        hosts=("googlevideo.com", "ytimg.com"),
        referers=("https://www.youtube.com/",),
    ),
    MediaSource(
        hosts=("redd.it", "redditmedia.com"),
        referers=("https://www.reddit.com/",),
    ),
    MediaSource(hosts=("sndcdn.com",), referers=("https://soundcloud.com/",)),
    MediaSource(
        hosts=("cdninstagram.com", "fbcdn.net"),
        referers=("https://www.instagram.com/",),
    ),
    MediaSource(hosts=("scdn.co",), referers=("https://open.spotify.com/",)),
    MediaSource(hosts=("pinimg.com",), referers=("https://www.pinterest.com/",)),
    MediaSource(
        hosts=("xhscdn.com", "xhscdn.net", "xiaohongshu.com"),
        referers=("https://www.xiaohongshu.com/",),
    ),
)

MEDIA_HOSTS = tuple(host for source in MEDIA_SOURCES for host in source.hosts)
TWITTER_MEDIA_HOSTS = ("twimg.com",)
XHS_IMAGE_HOSTS = ("xhscdn.com", "xhscdn.net")
