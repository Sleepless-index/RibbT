import html
import re
from urllib.parse import urlparse

import requests

from ..config import DESKTOP_UA
from ..services.candidates import candidate, clean_url, dedupe
from ..services.net import get_session
from .base import PlatformAdapter, new_post, video_group

TITLE_LIMIT = 80
POST_RE = re.compile(r"/posts/(\d+)")
VIDEO_EXTENSIONS = ("mp4", "webm")
# Danbooru asks API clients to say who they are, and its bot filter dislikes a browser User-Agent sent by a
# non-browser client, so a plain app name goes first and a browser one is the fallback.
USER_AGENTS = (
    "RibbT/1.0 (+https://github.com/Sleepless-Index/RibbT)",
    DESKTOP_UA,
)
ATTR_RE = r'data-%s="([^"]+)"'
OG_IMAGE_RE = re.compile(r'<meta[^>]+property="og:image"[^>]+content="([^"]+)"', re.I)


def post_id(url):
    match = POST_RE.search(urlparse(url).path)
    return match.group(1) if match else None


def _names(tag_string, limit):
    """Readable names from a space-separated tag string: 'hatsune_miku_(vocaloid)' -> 'Hatsune Miku'."""
    names = []
    for tag in (tag_string or "").split():
        name = re.sub(r"_\([^)]*\)$", "", tag).replace("_", " ").strip()
        if name and name.title() not in names:
            names.append(name.title())
    return names[:limit]


class DanbooruAdapter(PlatformAdapter):
    name = "danbooru"
    label = "Danbooru"
    features = ("Photos", "Video")
    hosts = ("donmai.us",)
    note = "Gold-only and banned posts can't be read."

    def extract(self, url):
        pid = post_id(url)
        if not pid:
            raise ValueError("That doesn't look like a Danbooru post link. Open a single image and copy its link.")

        post, status = self._fetch_json(pid)
        if post is not None:
            return self._from_json(pid, post)

        # The API refused or failed: the post page carries the same file links.
        page_post, page_status = self._fetch_page(pid)
        if page_post is not None:
            return page_post
        status = status or page_status
        if status == 404:
            raise ValueError(f"Danbooru says post #{pid} doesn't exist. It may have been deleted.")
        if status:
            raise ValueError(
                f"Danbooru refused the request (HTTP {status}). It may be rate limiting or blocking this network. Try again later."
            )
        raise ValueError("Could not reach Danbooru. Check the connection and try again.")

    # -- API ----------------------------------------------------------------------------------

    @staticmethod
    def _fetch_json(pid):
        """(post dict, None) on success, else (None, HTTP status or None)."""
        status = None
        for agent in USER_AGENTS:
            try:
                response = get_session().get(
                    f"https://danbooru.donmai.us/posts/{pid}.json",
                    headers={"User-Agent": agent, "Accept": "application/json"},
                    timeout=15,
                )
            except requests.RequestException:
                continue
            status = response.status_code
            if response.ok:
                try:
                    data = response.json()
                except ValueError:
                    continue
                if isinstance(data, dict) and data.get("id"):
                    return data, None
            elif status == 404:
                break
        return None, status

    def _from_json(self, pid, post):
        extension = (post.get("file_ext") or "").lower()
        files = [post.get("file_url"), post.get("large_file_url")]
        if extension == "zip":
            # An animated "ugoira": the site publishes a video version as the large file.
            files = [post.get("large_file_url")]
        urls = dedupe([clean_url(u) for u in files if u])
        if not urls:
            if post.get("is_banned"):
                raise ValueError("That Danbooru post is banned, so its file is hidden.")
            if post.get("is_deleted"):
                raise ValueError("That Danbooru post was deleted.")
            raise ValueError("Danbooru hides that post's file from guests (it may need a Gold account).")

        artists = _names(post.get("tag_string_artist"), 2)
        characters = _names(post.get("tag_string_character"), 2)
        title = ", ".join(characters) or ", ".join(_names(post.get("tag_string_copyright"), 1)) or f"Danbooru #{pid}"
        preview = clean_url(post.get("preview_file_url")) if post.get("preview_file_url") else None
        return self._build(pid, urls, title, ", ".join(artists), preview)

    # -- Post page ----------------------------------------------------------------------------

    @staticmethod
    def _fetch_page(pid):
        status = None
        for agent in USER_AGENTS:
            try:
                response = get_session().get(
                    f"https://danbooru.donmai.us/posts/{pid}",
                    headers={"User-Agent": agent, "Accept": "text/html,*/*;q=0.8"},
                    timeout=15,
                )
            except requests.RequestException:
                continue
            status = response.status_code
            if not response.ok:
                if status == 404:
                    break
                continue
            page = response.text

            def attr(name):
                match = re.search(ATTR_RE % name, page)
                return clean_url(html.unescape(match.group(1))) if match else None

            urls = [attr("file-url"), attr("large-file-url")]
            if not any(urls):
                og = OG_IMAGE_RE.search(page)
                urls = [clean_url(html.unescape(og.group(1)))] if og else []
            urls = dedupe([u for u in urls if u])
            if urls:
                return DanbooruAdapter._build(pid, urls, f"Danbooru #{pid}", "", None), None
        return None, status

    @staticmethod
    def _build(pid, urls, title, author, preview):
        is_video = urls[0].lower().split("?")[0].endswith(tuple("." + e for e in VIDEO_EXTENSIONS))
        result = new_post(id=pid, title=title[:TITLE_LIMIT], author=author, kind="video" if is_video else "images")
        if is_video:
            result["_videos"] = [video_group([candidate(urls[0])])]
        else:
            result["_images"] = [urls]
            result["_cover"] = dedupe([preview] + urls) if preview else urls
        return result
