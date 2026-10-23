import html
import re
from urllib.parse import parse_qs, urlparse
import xml.etree.ElementTree as ET

import requests

from ..config import DESKTOP_PAGE_HEADERS, DESKTOP_UA
from ..services.candidates import candidate, clean_url, dedupe
from ..services.net import get_session
from .base import PlatformAdapter, new_post, video_group

SITE = "https://safebooru.org"
API = SITE + "/index.php"
VIDEO_EXTENSIONS = (".mp4", ".webm")
OG_RE = r'<meta[^>]+(?:property|name)="%s"[^>]+content="([^"]+)"'
ID_RE = re.compile(r"[?&]id=(\d+)")
FULL_IMAGE_RE = re.compile(r'<a[^>]+href="([^"]+)"[^>]*>\s*Original image', re.I)
MAIN_IMAGE_RE = re.compile(r'<(?:img|video|source)[^>]+id="(?:image|gelcomVideoPlayer)"[^>]+src="([^"]+)"', re.I)
API_HEADERS = {"User-Agent": DESKTOP_UA, "Accept": "application/json, text/xml;q=0.9, */*;q=0.5"}


def post_id(url):
    """The numeric post id from a view link like index.php?page=post&s=view&id=123."""
    parsed = urlparse(url)
    values = parse_qs(parsed.query).get("id")
    if values and values[0].isdigit():
        return values[0]
    match = ID_RE.search(url)
    return match.group(1) if match else None


def _absolute(url):
    url = html.unescape(url or "").strip()
    if not url:
        return None
    if url.startswith("/") and not url.startswith("//"):
        url = SITE + url
    return clean_url(url)


def _file_urls(item):
    """Original first, then the resized sample, from one API record."""
    directory, name = item.get("directory"), item.get("image")
    original = item.get("file_url")
    if not original and directory and name:
        original = f"{SITE}/images/{directory}/{name}"
    urls = [_absolute(original)]
    if str(item.get("sample")) in ("1", "true", "True") and directory and item.get("hash"):
        urls.append(f"{SITE}/samples/{directory}/sample_{item['hash']}.jpg")
    elif item.get("sample_url"):
        urls.append(_absolute(item["sample_url"]))
    return dedupe([u for u in urls if u])


def _records(response):
    """Post records from either the JSON or the XML answer of the dapi endpoint."""
    try:
        data = response.json()
    except ValueError:
        data = None
    if isinstance(data, dict):
        data = data.get("post") or []
    if isinstance(data, list):
        return [item for item in data if isinstance(item, dict)]
    try:
        root = ET.fromstring(response.text)
    except ET.ParseError:
        return []
    return [dict(node.attrib) for node in root.iter("post")]


class SafebooruAdapter(PlatformAdapter):
    name = "safebooru"
    label = "Safebooru"
    features = ("Photos",)
    hosts = ("safebooru.org",)

    def extract(self, url):
        pid = post_id(url)
        if not pid:
            raise ValueError("That doesn't look like a Safebooru post link. Open a single image and copy its link.")

        item = self._fetch_record(pid)
        if item:
            urls = _file_urls(item)
            if urls:
                return self._build(pid, urls, item.get("owner") or "", item.get("tags") or "")

        try:
            page = self.fetch_page(f"{API}?page=post&s=view&id={pid}", DESKTOP_PAGE_HEADERS)[1]
        except (ValueError, requests.RequestException):
            page = ""
        urls = self._urls_from_page(page)
        if urls:
            return self._build(pid, urls, "", "")
        raise ValueError("Could not read that Safebooru post. It may have been deleted.")

    @staticmethod
    def _fetch_record(pid):
        try:
            response = get_session().get(
                API,
                params={"page": "dapi", "s": "post", "q": "index", "id": pid, "json": 1},
                headers=API_HEADERS,
                timeout=15,
            )
            if not response.ok:
                return None
            records = _records(response)
        except requests.RequestException:
            return None
        return next((r for r in records if str(r.get("id")) == pid), records[0] if records else None)

    @staticmethod
    def _urls_from_page(page):
        if not page:
            return []
        found = []
        full = FULL_IMAGE_RE.search(page)
        if full:
            found.append(_absolute(full.group(1)))
        main = MAIN_IMAGE_RE.search(page)
        if main:
            found.append(_absolute(main.group(1)))
        og = re.search(OG_RE % "og:image", page)
        if og:
            found.append(_absolute(og.group(1)))
        return dedupe([u for u in found if u])

    @staticmethod
    def _build(pid, urls, owner, tags):
        is_video = urls[0].lower().split("?")[0].endswith(VIDEO_EXTENSIONS)
        post = new_post(id=pid, title=f"Safebooru #{pid}", author=owner, kind="video" if is_video else "images")
        if is_video:
            post["_videos"] = [video_group([candidate(urls[0])])]
        else:
            post["_images"] = [urls]
            post["_cover"] = urls
        return post
