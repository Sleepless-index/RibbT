import re

from .bilibili import BilibiliAdapter
from .danbooru import DanbooruAdapter
from .gelbooru import GelbooruAdapter
from .instagram import InstagramAdapter
from .pinterest import PinterestAdapter
from .rednote import RedNoteAdapter
from .reddit import RedditAdapter
from .safebooru import SafebooruAdapter
from .soundcloud import SoundCloudAdapter
from .spotify import SpotifyAdapter
from .tiktok import TikTokAdapter
from .twitter import TwitterAdapter
from .youtube import YouTubeAdapter

ADAPTERS = (
    RedNoteAdapter(),
    TwitterAdapter(),
    BilibiliAdapter(),
    TikTokAdapter(),
    YouTubeAdapter(),
    RedditAdapter(),
    PinterestAdapter(),
    SpotifyAdapter(),
    SoundCloudAdapter(),
    InstagramAdapter(),
    SafebooruAdapter(),
    DanbooruAdapter(),
    GelbooruAdapter(),
)

LINK_PATH = r"(?:/[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]*)?"
TRAILING_PUNCTUATION = ".,;:!?)'\""


def _build_link_pattern(adapters):
    hosts = sorted({host for adapter in adapters for host in adapter.hosts}, key=len, reverse=True)
    alternatives = "|".join(re.escape(host) for host in hosts)
    return re.compile(
        r"(?<![\w.-])(?:https?://)?(?:[\w-]+\.)*(?:" + alternatives + r")(?![\w-]|\.[\w-])"
        + LINK_PATH,
        re.I,
    )


LINK_RE = _build_link_pattern(ADAPTERS)


def find_link(text):
    match = LINK_RE.search(text or "")
    if not match:
        return None
    url = match.group(0).rstrip(TRAILING_PUNCTUATION)
    return url if url.lower().startswith("http") else "https://" + url


def pick_adapter(url):
    if not url:
        return None
    return next((adapter for adapter in ADAPTERS if adapter.matches(url)), None)


def platform_catalog():
    return [
        {"name": a.name, "label": a.label, "hosts": list(a.hosts),
         "display": list(a.display_hosts or a.hosts),
         "subtitles": bool(getattr(a, "subtitles", False)),
         "features": list(a.features), "note": a.note}
        for a in ADAPTERS
    ]
