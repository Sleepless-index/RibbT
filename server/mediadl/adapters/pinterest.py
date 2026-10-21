import json
import re
import requests

from ..config import DESKTOP_PAGE_HEADERS, DESKTOP_UA
from ..services.candidates import candidate, dedupe
from ..services.net import get_session
from .base import PlatformAdapter, new_post, video_group

TITLE_LIMIT = 80
PIN_ID_RE = re.compile(r"/pin/(?:[^/?#]*--)?(\d+)")
PINIMG_SIZE_RE = re.compile(r"^(https://i\.pinimg\.com)/(?:\d+x(?:\d+)?|\d+x)/")
HLS_RE = re.compile(r"^(https://v\d*\.pinimg\.com/videos/[^/]+)/hls/(.+)\.m3u8$")
OG_RE = r'<meta[^>]+(?:property|name)="%s"[^>]+content="([^"]+)"'

API_HEADERS = {
    "User-Agent": DESKTOP_UA,
    "Accept": "application/json",
    "X-Requested-With": "XMLHttpRequest",
    "X-Pinterest-PWS-Handler": "www/pin/[id].js",
}


def to_original(url):
    """Rewrite an i.pinimg.com size variant to its /originals/ file."""
    return PINIMG_SIZE_RE.sub(r"\1/originals/", url) if url else url


def image_urls(images):
    """Candidate URLs for one image dict ({'orig': {...}, '736x': {...}}), best first."""
    if not isinstance(images, dict):
        return []
    sized = [v for k, v in images.items() if isinstance(v, dict) and v.get("url")]
    sized.sort(key=lambda v: v.get("width") or 0, reverse=True)
    urls = [(images.get("orig") or {}).get("url")]
    urls += [to_original(v["url"]) for v in sized[:1]]
    urls += [v["url"] for v in sized]
    return dedupe([u for u in urls if u])


def video_candidates(video_list):
    """MP4 candidates from a video_list, plus an MP4 guessed from an HLS-only URL."""
    if not isinstance(video_list, dict):
        return []
    mp4s, hls = [], []
    for item in video_list.values():
        url = (item or {}).get("url") if isinstance(item, dict) else None
        if not url:
            continue
        if url.lower().split("?")[0].endswith(".mp4"):
            mp4s.append(item)
        elif ".m3u8" in url.lower():
            hls.append(item)

    mp4s.sort(key=lambda i: (i.get("height") or 0, i.get("width") or 0), reverse=True)
    candidates = [
        candidate(i["url"], codec="h264", height=i.get("height") or None) for i in mp4s
    ]
    if not candidates:
        for item in hls:
            match = HLS_RE.match(item["url"])
            if match:
                candidates.append(candidate(f"{match.group(1)}/720p/{match.group(2)}.mp4", codec="h264", height=720))
                break
    return candidates


def _pin_media(pin):
    """(videos, images) found in a pin payload; covers plain, carousel and idea pins."""
    videos = []
    images = []

    cands = video_candidates((pin.get("videos") or {}).get("video_list"))
    if cands:
        videos.append(video_group(cands))

    pages = ((pin.get("story_pin_data") or {}).get("pages")) or []
    for page in pages:
        for block in page.get("blocks") or []:
            vlist = ((block.get("video") or {}).get("video_list"))
            cands = video_candidates(vlist)
            if cands:
                videos.append(video_group(cands))
            elif block.get("image"):
                urls = image_urls((block["image"] or {}).get("images"))
                if urls:
                    images.append(urls)

    for slide in ((pin.get("carousel_data") or {}).get("carousel_slides")) or []:
        urls = image_urls(slide.get("images"))
        if urls:
            images.append(urls)

    if not videos and not images:
        urls = image_urls(pin.get("images"))
        if urls:
            images.append(urls)
    return videos, images


class PinterestAdapter(PlatformAdapter):
    name = "pinterest"
    label = "Pinterest"
    hosts = (
        "pinterest.com", "pin.it",
        "pinterest.at", "pinterest.ca", "pinterest.ch", "pinterest.cl", "pinterest.de",
        "pinterest.dk", "pinterest.es", "pinterest.fr", "pinterest.ie", "pinterest.it",
        "pinterest.jp", "pinterest.nl", "pinterest.nz", "pinterest.ph", "pinterest.pt",
        "pinterest.ru", "pinterest.se", "pinterest.co.kr", "pinterest.co.uk",
        "pinterest.com.au", "pinterest.com.br", "pinterest.com.mx",
    )
    display_hosts = ("pinterest.com", "pin.it", "regional domains (.ph, .co.uk, …)")

    def extract(self, url):
        page_html = None
        if not PIN_ID_RE.search(url):
            try:
                url, page_html = self.fetch_page(url, DESKTOP_PAGE_HEADERS)
            except (ValueError, requests.RequestException):
                pass
        match = PIN_ID_RE.search(url)
        if not match:
            raise ValueError("That doesn't look like a Pinterest pin link.")
        pin_id = match.group(1)

        pin = self._fetch_api(pin_id)
        if pin:
            post = self._build(pin_id, pin)
            if post:
                return post

        if page_html is None:
            try:
                page_html = self.fetch_page(f"https://www.pinterest.com/pin/{pin_id}/", DESKTOP_PAGE_HEADERS)[1]
            except (ValueError, requests.RequestException):
                page_html = ""
        post = self._from_html(pin_id, page_html)
        if post:
            return post
        raise ValueError("Could not read that pin. It may be private, removed, or Pinterest blocked the request.")

    @staticmethod
    def _fetch_api(pin_id):
        options = json.dumps(
            {"options": {"id": pin_id, "field_set_key": "detailed"}, "context": {}},
            separators=(",", ":"),
        )
        try:
            response = get_session().get(
                "https://www.pinterest.com/resource/PinResource/get/",
                params={"source_url": f"/pin/{pin_id}/", "data": options},
                headers=API_HEADERS,
                timeout=15,
            )
            data = response.json()["resource_response"]["data"] if response.ok else None
            return data if isinstance(data, dict) else None
        except (requests.RequestException, ValueError, KeyError, TypeError):
            return None

    @staticmethod
    def _build(pin_id, pin):
        videos, images = _pin_media(pin)
        if not videos and not images:
            return None
        pinner = pin.get("pinner") or {}
        post = new_post(
            id=str(pin.get("id") or pin_id),
            title=str(pin.get("title") or pin.get("grid_title") or pin.get("description") or "")[:TITLE_LIMIT],
            author=pinner.get("full_name") or pinner.get("username") or "",
            avatar=pinner.get("image_small_url") or "",
            kind="video" if videos else "images",
        )
        post["_videos"] = videos
        post["_images"] = images
        post["_cover"] = image_urls(pin.get("images")) or (images[0] if images else None)
        return post

    @staticmethod
    def _from_html(pin_id, page_html):
        if not page_html:
            return None
        title = re.search(OG_RE % "og:title", page_html)
        post = new_post(id=pin_id, title=(title.group(1) if title else "")[:TITLE_LIMIT], kind="images")

        videos = []
        for blob in re.findall(r'"video_list"\s*:\s*(\{.*?\})\s*[,}]', page_html, re.S):
            try:
                cands = video_candidates(json.loads(blob))
            except ValueError:
                continue
            if cands:
                videos.append(video_group(cands))
                break
        if not videos:
            og_video = re.search(OG_RE % "og:video", page_html)
            if og_video and og_video.group(1).lower().split("?")[0].endswith(".mp4"):
                videos.append(video_group([candidate(og_video.group(1), codec="h264")]))

        cover = re.search(OG_RE % "og:image", page_html)
        urls = dedupe([to_original(cover.group(1)), cover.group(1)]) if cover else []

        if videos:
            post["kind"] = "video"
            post["_videos"] = videos
        elif urls:
            post["_images"] = [urls]
        else:
            return None
        post["_cover"] = urls or None
        return post
