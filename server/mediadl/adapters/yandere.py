import html
import re
from urllib.parse import urlparse

import requests

from ..config import DESKTOP_PAGE_HEADERS, DESKTOP_UA
from ..services.candidates import candidate, clean_url, dedupe
from ..services.net import get_session
from .base import PlatformAdapter, new_post, video_group

SITE = "https://yande.re"
POST_RE = re.compile(r"/post/show/(\d+)")
VIDEO_EXTENSIONS = ("mp4", "webm")
HIGHRES_RE = re.compile(r'<a[^>]+(?:id="highres"|class="[^"]*original-file[^"]*")[^>]+href="([^"]+)"', re.I)
HIGHRES_ALT_RE = re.compile(r'<a[^>]+href="([^"]+)"[^>]+id="highres"', re.I)
OG_IMAGE_RE = re.compile(r'<meta[^>]+property="og:image"[^>]+content="([^"]+)"', re.I)
API_HEADERS = {"User-Agent": DESKTOP_UA, "Accept": "application/json"}


def post_id(url):
    match = POST_RE.search(urlparse(url).path)
    return match.group(1) if match else None


def _absolute(url):
    url = html.unescape(url or "").strip()
    if not url:
        return None
    if url.startswith("//"):
        url = "https:" + url
    elif url.startswith("/"):
        url = SITE + url
    return clean_url(url)


class YandereAdapter(PlatformAdapter):
    """yande.re runs Moebooru: a single post is /post/show/ID and post.json?tags=id:ID gives its files."""

    name = "yandere"
    label = "Yande.re"
    features = ("Photos",)
    hosts = ("yande.re",)
    note = "Single posts only."

    def extract(self, url):
        pid = post_id(url)
        if not pid:
            raise ValueError("That doesn't look like a Yande.re post link. Open a single image and copy its link.")

        item = self._fetch_record(pid)
        if item:
            files = [_absolute(item.get("file_url")), _absolute(item.get("jpeg_url")), _absolute(item.get("sample_url"))]
            files = dedupe([f for f in files if f])
            if files:
                cover = dedupe([f for f in (_absolute(item.get("sample_url")), _absolute(item.get("jpeg_url"))) if f] + files)
                return self._build(pid, files, cover, item.get("author") or "", (item.get("file_ext") or "").lower())

        try:
            page = self.fetch_page(f"{SITE}/post/show/{pid}", DESKTOP_PAGE_HEADERS)[1]
        except (ValueError, requests.RequestException):
            page = ""
        files = self._files_from_page(page)
        if files:
            return self._build(pid, files, files, "", "")
        raise ValueError("Could not read that Yande.re post. It may have been deleted, or the site is blocking this connection.")

    @staticmethod
    def _fetch_record(pid):
        try:
            response = get_session().get(
                f"{SITE}/post.json", params={"tags": f"id:{pid}", "limit": 1}, headers=API_HEADERS, timeout=15,
            )
            if not response.ok:
                return None
            data = response.json()
        except (requests.RequestException, ValueError):
            return None
        if isinstance(data, dict):
            data = data.get("posts") or []
        records = [r for r in data if isinstance(r, dict)] if isinstance(data, list) else []
        return next((r for r in records if str(r.get("id")) == pid), None)

    @staticmethod
    def _files_from_page(page):
        if not page:
            return []
        found = []
        for pattern in (HIGHRES_RE, HIGHRES_ALT_RE, OG_IMAGE_RE):
            match = pattern.search(page)
            if match:
                found.append(_absolute(match.group(1)))
        return dedupe([f for f in found if f])

    @staticmethod
    def _build(pid, files, cover, author, extension):
        is_video = extension in VIDEO_EXTENSIONS or files[0].lower().split("?")[0].endswith(tuple("." + e for e in VIDEO_EXTENSIONS))
        post = new_post(id=pid, title=f"Yande.re #{pid}", author=author, kind="video" if is_video else "images")
        if is_video:
            post["_videos"] = [video_group([candidate(files[0])])]
        else:
            post["_images"] = [files]
            post["_cover"] = cover
        return post
