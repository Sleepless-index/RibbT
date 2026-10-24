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
API_HEADERS = {"User-Agent": DESKTOP_UA, "Accept": "application/json"}


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
        post = self._fetch(pid)
        if post is None:
            raise ValueError("Could not read that Danbooru post. It may have been deleted.")

        extension = (post.get("file_ext") or "").lower()
        files = [post.get("file_url"), post.get("large_file_url")]
        if extension == "zip":
            # An animated "ugoira": the site publishes a video version as the large file.
            files = [post.get("large_file_url")]
        urls = dedupe([clean_url(u) for u in files if u])
        if not urls:
            raise ValueError("That post's file is not available to guests (it may need a Gold account).")

        artists = _names(post.get("tag_string_artist"), 2)
        characters = _names(post.get("tag_string_character"), 2)
        title = ", ".join(characters) or ", ".join(_names(post.get("tag_string_copyright"), 1)) or f"Danbooru #{pid}"
        is_video = urls[0].lower().split("?")[0].endswith(tuple("." + e for e in VIDEO_EXTENSIONS))
        result = new_post(
            id=pid,
            title=title[:TITLE_LIMIT],
            author=", ".join(artists),
            kind="video" if is_video else "images",
        )
        if is_video:
            result["_videos"] = [video_group([candidate(urls[0])])]
        else:
            result["_images"] = [urls]
            result["_cover"] = dedupe([clean_url(post.get("preview_file_url"))] + urls) if post.get("preview_file_url") else urls
        return result

    @staticmethod
    def _fetch(pid):
        try:
            response = get_session().get(
                f"https://danbooru.donmai.us/posts/{pid}.json", headers=API_HEADERS, timeout=15
            )
            data = response.json() if response.ok else None
        except (requests.RequestException, ValueError):
            return None
        return data if isinstance(data, dict) and data.get("id") else None
