import re
from urllib.parse import urlparse

from ..services.candidates import clean_url
from .base import PlatformAdapter, new_post, video_group
from .ytdlp import best_audio_url, clock_text, dump_infos, video_candidates

TITLE_LIMIT = 80
POST_PATH_RE = re.compile(r"^/(?:[\w.]+/)?(?:p|reel|reels|tv)/([\w-]+)")
IMAGE_EXTS = ("jpg", "jpeg", "png", "webp")


def _by_short_side(formats):
    """Label vertical videos by their short side (720p, not 1280p), like every other site."""
    fixed = []
    for fmt in formats:
        width, height = fmt.get("width"), fmt.get("height")
        fixed.append({**fmt, "height": min(width, height)} if width and height else fmt)
    return fixed


def _caption_title(entry):
    for key in ("description", "title"):
        lines = (entry.get(key) or "").strip().splitlines()
        if lines and lines[0].strip():
            return lines[0].strip()[:TITLE_LIMIT]
    return ""


def _image_url(entry):
    url = clean_url(entry.get("url"))
    if url and (entry.get("ext") or "").lower() in IMAGE_EXTS:
        return url
    return clean_url(entry.get("thumbnail"))


class InstagramAdapter(PlatformAdapter):
    """Posts, reels and carousels through yt-dlp. Most of Instagram wants a signed-in session,
    so importing a cookies.txt (Settings → Server → Sign-in cookies) is usually needed."""

    name = "instagram"
    label = "Instagram"
    hosts = ("instagram.com", "instagr.am")

    def matches(self, url):
        return super().matches(url) and bool(POST_PATH_RE.match(urlparse(url).path))

    def extract(self, url):
        entries = dump_infos(url, "Instagram", ("--no-playlist",))
        first = entries[0]
        post = new_post(
            id=str(first.get("display_id") or first.get("id") or ""),
            title=_caption_title(first),
            author=first.get("uploader") or first.get("channel") or first.get("uploader_id") or "",
        )
        for entry in entries:
            formats = _by_short_side(entry.get("formats") or [])
            candidates = video_candidates(formats, best_audio_url(formats)) if formats else []
            if candidates:
                post["_videos"].append(video_group(candidates))
                continue
            image = _image_url(entry)
            if image:
                post["_images"].append([image])
        if not post["_videos"] and not post["_images"]:
            raise ValueError("No downloadable media found in that Instagram post.")
        if post["_videos"]:
            post["kind"] = "video"
            post["duration"] = clock_text(first.get("duration"))
            cover = clean_url(first.get("thumbnail"))
            post["_cover"] = [cover] if cover else None
        return post
