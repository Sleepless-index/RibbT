import html
import re
from urllib.parse import urlparse

from ..config import DESKTOP_UA
from ..services.net import fetch_page, get_session
from .base import PlatformAdapter
from .youtube import YouTubeAdapter
from .soundcloud import audio_post
from .ytdlp import dump_infos

TRACK_RE = re.compile(r"^/(?:intl-[a-z-]+/)?track/([A-Za-z0-9]{22})/?$")
OG_RE = r'<meta[^>]+property=["\']og:%s["\'][^>]+content=["\']([^"\']*)["\']'
TITLE_LIMIT = 80
# Spotify sends phones to an app-install page on another host, so ask like a desktop browser.
PAGE_HEADERS = {
    "User-Agent": DESKTOP_UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
}
PAGE_HOSTS = ("spotify.com",)
DURATION_SLACK = 12
OEMBED = "https://open.spotify.com/oembed"


def _og(page, name):
    match = re.search(OG_RE % name, page, re.I)
    if not match:
        # Some pages put content before property.
        match = re.search(r'<meta[^>]+content=["\']([^"\']*)["\'][^>]+property=["\']og:%s["\']' % name, page, re.I)
    return html.unescape(match.group(1)).strip() if match else ""


def parse_track_page(page):
    """(title, artist, album, cover) from a Spotify track page's share tags."""
    title = _og(page, "title")
    # "Artist · Album · Song · 2021" (the artist is first, the album second).
    parts = [part.strip() for part in _og(page, "description").split("·")]
    artist = parts[0] if parts else ""
    album = parts[1] if len(parts) > 2 else ""
    return title, artist, album, _og(page, "image")


def _duration(page):
    match = re.search(r'<meta[^>]+(?:name|property)=["\']music:duration["\'][^>]+content=["\'](\d+)["\']', page, re.I)
    return int(match.group(1)) if match else None


def _read_track(adapter, url):
    """(title, artist, album, cover, seconds) from the page's share tags, or from oEmbed when it won't open."""
    clean = "https://open.spotify.com" + urlparse(url).path
    try:
        _, page = adapter.fetch_page(clean, PAGE_HEADERS)
        title, artist, album, cover = parse_track_page(page)
        if title:
            return title, artist, album, cover, _duration(page)
    except Exception:
        pass
    try:
        data = get_session().get(OEMBED, params={"url": clean}, headers=PAGE_HEADERS, timeout=15).json()
        return (data.get("title") or "").strip(), "", "", data.get("thumbnail_url") or "", None
    except Exception:
        return "", "", "", "", None


def _near(info, seconds):
    length = info.get("duration")
    return bool(seconds and length and abs(length - seconds) <= DURATION_SLACK)


class SpotifyAdapter(PlatformAdapter):
    """Spotify streams are protected, so a track link is resolved by its title and artist.

    The track's details are read from Spotify's public page and a matching copy is found on
    YouTube, then on SoundCloud. The result keeps Spotify's title, artist and cover.
    """

    name = "spotify"
    label = "Spotify"
    hosts = ("open.spotify.com",)
    # Pages may bounce through other spotify.com hosts before the track page.
    display_hosts = ("spotify.com",)

    def fetch_page(self, url, headers=None):
        return fetch_page(url, PAGE_HOSTS, headers)

    def rank(self, candidates, prefs):
        return YouTubeAdapter().rank(candidates, prefs)

    def matches(self, url):
        return super().matches(url) and bool(TRACK_RE.match(urlparse(url).path))

    def extract(self, url):
        title, artist, album, cover, seconds = _read_track(self, url)
        if not title:
            raise ValueError("Could not read that Spotify track.")

        query = f"{artist} {title}".strip()
        post = self._from_youtube(query, seconds) or self._from_soundcloud(query, seconds)
        if post is None:
            raise ValueError("Could not find a downloadable match for that Spotify track.")
        post["title"] = title[:TITLE_LIMIT]
        post["author"] = artist or post["author"]
        post["album"] = album
        post["track_id"] = TRACK_RE.match(urlparse(url).path).group(1)
        if cover:
            post["_cover"] = [cover, *(post["_cover"] or [])]
        return post

    @staticmethod
    def _search(prefix, query, seconds):
        """Search results, those with the right length first. A failed search just means no results."""
        try:
            infos = dump_infos(f"{prefix}4:{query}", "Spotify")
        except ValueError:
            return []
        return sorted(infos, key=lambda info: not _near(info, seconds))

    def _from_youtube(self, query, seconds):
        # The first match sometimes has no stream RibbT can use, so the next few are tried in order.
        youtube = YouTubeAdapter()
        for info in self._search("ytsearch", f"{query} audio", seconds):
            try:
                return youtube.post_from_info(info)
            except ValueError:
                continue
        return None

    def _from_soundcloud(self, query, seconds):
        # Uploads there are less reliable, so with a known length only a matching one is accepted.
        for info in self._search("scsearch", query, seconds):
            if seconds and info.get("duration") and not _near(info, seconds):
                continue
            try:
                return audio_post(info)
            except ValueError:
                continue
        return None
