import html
import os
import re
from urllib.parse import urlparse

import requests

from ..config import DESKTOP_PAGE_HEADERS, DESKTOP_UA
from ..services.candidates import candidate, clean_url, dedupe
from ..services.net import get_session
from .base import PlatformAdapter, new_post, video_group

TITLE_LIMIT = 80
# Imgur's public anonymous API wants a client id; this is the widely shared anonymous one. Set IMGUR_CLIENT_ID to use your own.
CLIENT_ID = os.environ.get("IMGUR_CLIENT_ID", "546c25a59c58ad7")
API = "https://api.imgur.com/3"
ID = r"([A-Za-z0-9]{5,8})"
DIRECT_RE = re.compile(r"^/" + ID + r"\.(jpg|jpeg|png|gif|gifv|webp|mp4|webm)$", re.I)
ALBUM_RE = re.compile(r"^/(?:a|gallery)/(?:[^/]*-)?" + ID + r"$", re.I)
SINGLE_RE = re.compile(r"^/(?:gallery/)?" + ID + r"$", re.I)
OG_RE = r'<meta[^>]+(?:property|name)="%s"[^>]+content="([^"]+)"'
VIDEO_EXTENSIONS = (".mp4", ".webm")


def _is_video(link, kind=""):
    return str(kind).startswith("video/") or link.lower().split("?")[0].endswith(VIDEO_EXTENSIONS)


def _media(entry):
    """(url, is_video) for one image record of the API."""
    link = clean_url(entry.get("mp4") or entry.get("link") or "")
    if not link:
        return None
    return link, _is_video(link, entry.get("type") or "")


class ImgurAdapter(PlatformAdapter):
    name = "imgur"
    label = "Imgur"
    features = ("Photos", "Video")
    hosts = ("imgur.com",)

    def extract(self, url):
        path = urlparse(url).path.rstrip("/") or "/"

        direct = DIRECT_RE.match(path)
        if direct:
            ident, extension = direct.group(1), direct.group(2).lower()
            if extension in ("gifv", "mp4", "webm"):
                return self._single(ident, [(f"https://i.imgur.com/{ident}.mp4", True)])
            return self._single(ident, [(f"https://i.imgur.com/{ident}.{extension}", False)])

        album = ALBUM_RE.match(path)
        if album:
            return self._album(album.group(1), "/gallery/" in path)
        single = SINGLE_RE.match(path)
        if single:
            return self._single_from_api(single.group(1))
        raise ValueError("That doesn't look like an Imgur picture or album link.")

    # -- API ----------------------------------------------------------------------------------

    @staticmethod
    def _api(endpoint):
        try:
            response = get_session().get(
                API + endpoint, headers={"Authorization": f"Client-ID {CLIENT_ID}", "User-Agent": DESKTOP_UA}, timeout=15,
            )
        except requests.RequestException:
            return None
        if not response.ok:
            return None
        try:
            data = response.json().get("data")
        except (ValueError, AttributeError):
            return None
        return data if isinstance(data, dict) else None

    def _album(self, ident, gallery):
        data = self._api(f"/album/{ident}") or (self._api(f"/gallery/album/{ident}") if gallery else None)
        if data and data.get("images"):
            media = [m for m in (_media(e) for e in data["images"] if isinstance(e, dict)) if m]
            if media:
                return self._compose(ident, data.get("title") or "", data.get("account_url") or "", media)
        if gallery:  # /gallery/ID may also be one picture
            single = self._single_from_api(ident, quiet=True)
            if single:
                return single
        return self._from_page(f"https://imgur.com/a/{ident}", ident)

    def _single_from_api(self, ident, quiet=False):
        data = self._api(f"/image/{ident}")
        if data:
            media = _media(data)
            if media:
                return self._compose(ident, data.get("title") or "", "", [media])
        if quiet:
            return None
        return self._from_page(f"https://imgur.com/{ident}", ident)

    # -- Page fallback ------------------------------------------------------------------------

    def _from_page(self, page_url, ident):
        try:
            page = self.fetch_page(page_url, DESKTOP_PAGE_HEADERS)[1]
        except (ValueError, requests.RequestException):
            page = ""
        found = []
        for prop, is_video in (("og:video", True), ("og:image", False)):
            match = re.search(OG_RE % prop, page or "")
            if match:
                link = clean_url(html.unescape(match.group(1)).split("?")[0])
                if link and "imgur.com" in link:
                    found.append((link, is_video or _is_video(link)))
        # Prefer the video when the page has one; the og:image is then just its still frame.
        media = [m for m in found if m[1]] or found[:1]
        if media:
            return self._compose(ident, "", "", media[:1])
        raise ValueError("Could not read that Imgur link. It may have been removed, or Imgur is limiting requests right now.")

    # -- Result -------------------------------------------------------------------------------

    def _single(self, ident, media):
        return self._compose(ident, "", "", media)

    @staticmethod
    def _compose(ident, title, author, media):
        videos = [url for url, is_video in media if is_video]
        images = [url for url, is_video in media if not is_video]
        post = new_post(
            id=ident, title=(title or f"Imgur {ident}")[:TITLE_LIMIT], author=author,
            kind="images" if images else "video",
        )
        post["_videos"] = [video_group([candidate(url)]) for url in videos]
        post["_images"] = [[url] for url in images]
        if images:
            post["_cover"] = [images[0]]
        return post
