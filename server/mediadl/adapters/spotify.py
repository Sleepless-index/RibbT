import html
import re
from urllib.parse import urlparse

from .base import PlatformAdapter
from .youtube import YouTubeAdapter
from .ytdlp import dump_info

TRACK_RE = re.compile(r"^/(?:intl-[a-z-]+/)?track/([A-Za-z0-9]{22})/?$")
OG_RE = r'<meta[^>]+property=["\']og:%s["\'][^>]+content=["\']([^"\']*)["\']'
TITLE_LIMIT = 80


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


class SpotifyAdapter(PlatformAdapter):
    """Spotify streams are protected, so a track link is resolved by its title and artist.

    The track's details are read from Spotify's public page, the best matching audio is found
    on YouTube, and the result keeps Spotify's title, artist and cover.
    """

    name = "spotify"
    label = "Spotify"
    hosts = ("open.spotify.com",)
    display_hosts = ("spotify.com",)

    def rank(self, candidates, prefs):
        return YouTubeAdapter().rank(candidates, prefs)

    def matches(self, url):
        return super().matches(url) and bool(TRACK_RE.match(urlparse(url).path))

    def extract(self, url):
        _, page = self.fetch_page(url)
        title, artist, album, cover = parse_track_page(page)
        if not title:
            raise ValueError("Could not read that Spotify track.")

        query = f"ytsearch1:{artist} {title} audio".strip()
        info = dump_info(query, "Spotify", ("--no-playlist",))
        post = YouTubeAdapter().post_from_info(info)
        post["title"] = title[:TITLE_LIMIT]
        post["author"] = artist or post["author"]
        post["album"] = album
        post["track_id"] = TRACK_RE.match(urlparse(url).path).group(1)
        if cover:
            post["_cover"] = [cover, *(post["_cover"] or [])]
        return post
