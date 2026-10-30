import re
from urllib.parse import parse_qs, urlparse

import requests

from ..config import DESKTOP_UA
from ..services.candidates import candidate, clean_url, dedupe
from ..services.net import get_session
from .base import PlatformAdapter, new_post, video_group
from .ytdlp import clock_text

SITE = "https://www.pixiv.net"
TITLE_LIMIT = 80
MAX_PAGES = 200
ARTWORK_RE = re.compile(r"/artworks/(\d+)")
SHORT_RE = re.compile(r"^/i/(\d+)$")
UGOIRA = 2
API_HEADERS = {
    "User-Agent": DESKTOP_UA,
    "Accept": "application/json",
    "Accept-Language": "en-US,en;q=0.9",
    "Referer": SITE + "/",
}


def artwork_id(url):
    parsed = urlparse(url)
    path = parsed.path.rstrip("/")
    for pattern in (ARTWORK_RE, SHORT_RE):
        match = pattern.search(path)
        if match:
            return match.group(1)
    query = parse_qs(parsed.query).get("illust_id") or []
    return query[0] if query and query[0].isdigit() else None


def api(path):
    """The ``body`` of one of Pixiv's public JSON endpoints, or None. Needs no sign-in for public works."""
    try:
        response = get_session().get(SITE + "/ajax" + path, headers=API_HEADERS, timeout=15)
        data = response.json()
    except (requests.RequestException, ValueError):
        return None
    if not isinstance(data, dict) or data.get("error"):
        return None
    body = data.get("body")
    return body if isinstance(body, (dict, list)) else None


class PixivAdapter(PlatformAdapter):
    """Public artworks: single images, multi-page posts and ugoira (animations). Restricted works need a sign-in."""

    name = "pixiv"
    label = "Pixiv"
    features = ("Photos", "Video")
    hosts = ("pixiv.net",)
    note = "Public artworks only."

    def extract(self, url):
        ident = artwork_id(url)
        if not ident:
            raise ValueError("That doesn't look like a Pixiv artwork link. Open one artwork and copy its link.")

        info = api(f"/illust/{ident}")
        urls = (info or {}).get("urls") or {}
        if not info or not (urls.get("original") or urls.get("regular")):
            raise ValueError(
                "Could not read that Pixiv artwork. It may be private or deleted, or restricted to signed-in users (such as R-18 works)."
            )

        title = str(info.get("title") or "").strip()[:TITLE_LIMIT] or f"Pixiv {ident}"
        author = str(info.get("userName") or "")
        cover = dedupe([clean_url(urls.get("regular")), clean_url(urls.get("small")), clean_url(urls.get("original"))])
        avatar = self._avatar(info.get("userId"))

        if info.get("illustType") == UGOIRA:
            return self._ugoira(ident, info, title, author, avatar, cover)

        post = new_post(id=ident, title=title, author=author, avatar=avatar, kind="images")
        post["_images"] = self._pages(ident, urls)
        post["_cover"] = cover
        return post

    @staticmethod
    def _pages(ident, single):
        pages = api(f"/illust/{ident}/pages")
        found = []
        for page in (pages if isinstance(pages, list) else [])[:MAX_PAGES]:
            page_urls = (page or {}).get("urls") or {}
            options = dedupe([clean_url(page_urls.get("original")), clean_url(page_urls.get("regular"))])
            if options:
                found.append(options)
        if found:
            return found
        options = dedupe([clean_url(single.get("original")), clean_url(single.get("regular"))])
        return [options] if options else []

    @staticmethod
    def _avatar(user_id):
        if not user_id:
            return ""
        user = api(f"/user/{user_id}?full=0")
        if isinstance(user, dict):
            return clean_url(user.get("imageBig") or user.get("image")) or ""
        return ""

    @staticmethod
    def _ugoira(ident, info, title, author, avatar, cover):
        meta = api(f"/illust/{ident}/ugoira_meta")
        meta = meta if isinstance(meta, dict) else {}
        zip_url = clean_url(meta.get("originalSrc") or meta.get("src"))
        frames = meta.get("frames") or []
        if not zip_url or not frames:
            raise ValueError("Could not read that Pixiv animation. Try again in a moment.")

        post = new_post(id=ident, title=title, author=author, avatar=avatar, kind="video")
        post["_cover"] = cover
        height = info.get("height")
        post["_videos"] = [
            video_group(
                [candidate(zip_url, height=height if isinstance(height, int) else None)],
                ugoira=True,
            )
        ]
        try:
            post["duration"] = clock_text(int(sum(int(f.get("delay") or 0) for f in frames) / 1000 + 0.5))
        except (TypeError, ValueError, AttributeError):
            pass
        return post
