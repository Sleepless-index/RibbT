import html
import re

import requests

from ..config import DESKTOP_UA
from ..services.candidates import candidate, dedupe
from ..services.net import get_session, host_ok
from .base import PlatformAdapter, new_post, video_group
from .ytdlp import best_audio_url, dump_info, video_candidates

TITLE_LIMIT = 80
POST_ID_RE = re.compile(r"/comments/([a-z0-9]+)", re.I)
SHORT_ID_RE = re.compile(r"^/([a-z0-9]{5,8})/?$", re.I)
REPRESENTATION_RE = re.compile(r"<Representation\b([^>]*)>\s*<BaseURL>([^<]+)</BaseURL>", re.I)
HEIGHT_ATTR_RE = re.compile(r'height="(\d+)"', re.I)
EXTENSIONS = {"image/jpg": "jpg", "image/jpeg": "jpg", "image/png": "png", "image/gif": "gif", "image/webp": "webp"}
VIDEO_HOSTS = ("v.redd.it",)
JSON_HEADERS = {"User-Agent": DESKTOP_UA, "Accept": "application/json"}


def _post_id(url):
    match = POST_ID_RE.search(url)
    if match:
        return match.group(1)
    from urllib.parse import urlparse
    parsed = urlparse(url)
    if (parsed.hostname or "").lower() in ("redd.it", "www.redd.it"):
        short = SHORT_ID_RE.match(parsed.path)
        if short:
            return short.group(1)
    return None


def _unescape(url):
    return html.unescape(url) if url else url


def _image_urls(item):
    """Candidate URLs for one gallery image, most original first."""
    source = item.get("s") or {}
    extension = EXTENSIONS.get(item.get("m") or "")
    urls = []
    if extension and item.get("id"):
        urls.append(f"https://i.redd.it/{item['id']}.{extension}")
    urls.append(_unescape(source.get("u") or source.get("gif")))
    return dedupe(urls)


class RedditAdapter(PlatformAdapter):
    name = "reddit"
    label = "Reddit"
    features = ('Video', 'Photos')
    hosts = ("reddit.com", "redd.it")

    def extract(self, url):
        if host_ok(url, VIDEO_HOSTS):
            return self._from_ytdlp(url)

        if "/s/" in url:
            try:
                url = self.fetch_page(url)[0]
            except (ValueError, requests.RequestException):
                pass

        post_id = _post_id(url)
        if not post_id:
            raise ValueError("That doesn't look like a Reddit post link.")

        data = self._fetch_json(post_id)
        if data is None:
            return self._from_ytdlp(url)
        return self._parse(data)

    @staticmethod
    def _fetch_json(post_id):
        try:
            response = get_session().get(
                f"https://www.reddit.com/comments/{post_id}/.json",
                params={"raw_json": 1, "limit": 1},
                headers=JSON_HEADERS,
                timeout=15,
            )
            payload = response.json() if response.ok else None
            return payload[0]["data"]["children"][0]["data"]
        except (requests.RequestException, ValueError, KeyError, IndexError, TypeError):
            return None

    def _parse(self, data):
        parents = data.get("crosspost_parent_list") or []
        source = parents[0] if parents else data

        post = new_post(
            id=str(source.get("id") or data.get("id") or ""),
            title=(source.get("title") or "")[:TITLE_LIMIT],
            author=source.get("author") or "",
            kind="images",
        )

        reddit_video = (source.get("secure_media") or {}).get("reddit_video") or (
            (source.get("preview") or {}).get("reddit_video_preview") or {}
        )
        gallery = source.get("gallery_data") or {}
        metadata = source.get("media_metadata") or {}

        if reddit_video.get("fallback_url"):
            post["kind"] = "video"
            post["_videos"].append(self._video_group(reddit_video))
        elif gallery.get("items"):
            for entry in gallery["items"]:
                item = metadata.get(entry.get("media_id")) or {}
                urls = _image_urls({**item, "id": entry.get("media_id")})
                if item.get("status", "valid") == "valid" and urls:
                    post["_images"].append(urls)
        else:
            target = _unescape(source.get("url_overridden_by_dest") or "")
            if re.match(r"https://i\.redd\.it/", target):
                post["_images"].append([target])
            else:
                previews = ((source.get("preview") or {}).get("images") or [])
                preview = _unescape(((previews[0] if previews else {}).get("source") or {}).get("url"))
                if preview and (source.get("post_hint") == "image" or target.endswith((".jpg", ".png", ".webp", ".gif"))):
                    post["_images"].append([preview])

        if not post["_videos"] and not post["_images"]:
            raise ValueError("No downloadable media in that Reddit post.")
        if post["_images"]:
            post["_cover"] = post["_images"][0]
        return post

    @staticmethod
    def _video_group(reddit_video):
        fallback = _unescape(reddit_video["fallback_url"])
        base = fallback.rsplit("/", 1)[0]
        audio_url = None
        heights = {}

        dash_url = _unescape(reddit_video.get("dash_url") or "")
        if dash_url and host_ok(dash_url, VIDEO_HOSTS):
            try:
                response = get_session().get(dash_url, headers=JSON_HEADERS, timeout=10)
                if response.ok and len(response.text) < 500_000:
                    for attrs, name in REPRESENTATION_RE.findall(response.text):
                        name = name.strip()
                        height = HEIGHT_ATTR_RE.search(attrs)
                        if "audio" in name.lower():
                            audio_url = audio_url or f"{base}/{name}"
                        elif height:
                            heights[int(height.group(1))] = f"{base}/{name}"
            except requests.RequestException:
                pass

        if not heights:
            heights[reddit_video.get("height") or 0] = fallback
        if not audio_url and not reddit_video.get("is_gif"):
            audio_url = f"{base}/DASH_AUDIO_128.mp4"

        candidates = []
        for height, url in sorted(heights.items(), reverse=True):
            entry = candidate(url, codec="h264", height=height or None)
            if audio_url:
                entry["audio"] = audio_url
            candidates.append(entry)
        return video_group(candidates)

    def _from_ytdlp(self, url):
        info = dump_info(url, "Reddit", ("--no-playlist",))
        formats = info.get("formats") or []
        candidates = video_candidates(formats, best_audio_url(formats))
        if not candidates:
            raise ValueError("No downloadable media in that Reddit post.")
        post = new_post(
            id=str(info.get("id") or ""),
            title=(info.get("title") or "")[:TITLE_LIMIT],
            author=info.get("uploader") or "",
            kind="video",
        )
        post["_videos"].append(video_group(candidates))
        if info.get("thumbnail"):
            post["_cover"] = [info["thumbnail"]]
        return post
