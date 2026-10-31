import html
import re
from urllib.parse import urlparse

import requests

from ..services.candidates import clean_url
from .base import PlatformAdapter, new_post, video_group
from .ytdlp import best_audio_url, clock_text, dump_infos, video_candidates

TITLE_LIMIT = 80
POST_PATH_RE = re.compile(r"^/(?:[\w.]+/)?(?:p|reel|reels|tv)/([\w-]+)")
IMAGE_EXTS = ("jpg", "jpeg", "png", "webp")
# yt-dlp's wording when a post has only photos and Instagram wasn't asked as a signed-in user.
NO_VIDEO = "no video in this post"
# The page Instagram serves to link-preview bots carries the post's picture and caption without a login.
PREVIEW_HEADERS = {
    "User-Agent": "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
    "Accept": "text/html",
}
OG = r'<meta[^>]+property="og:%s"[^>]+content="([^"]*)"'


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
    features = ('Video', 'Photos')
    note = 'Photos are small previews without sign-in cookies.'
    hosts = ("instagram.com", "instagr.am")

    def matches(self, url):
        return super().matches(url) and bool(POST_PATH_RE.match(urlparse(url).path))

    def extract(self, url):
        try:
            entries = dump_infos(url, "Instagram", ("--no-playlist",))
        except ValueError as error:
            if NO_VIDEO in str(error).lower():
                return self._photo_preview(url, error)
            raise
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

    def _photo_preview(self, url, original):
        """A photo post, which yt-dlp can't read without a sign-in. The preview page gives the first
        picture only, as a 640 px square crop: Instagram signs the larger sizes, so they can't be asked for."""
        try:
            page = self.fetch_page(url, PREVIEW_HEADERS)[1]
        except (ValueError, requests.RequestException):
            page = ""

        def meta(name):
            found = re.search(OG % name, page or "", re.I)
            return html.unescape(found.group(1)).strip() if found else ""

        image = clean_url(meta("image"))
        if not image:
            raise ValueError(
                "Could not read that Instagram photo. Instagram only shows photos to signed-in visitors: "
                "import your cookies.txt in Settings → Server to download it."
            ) from original

        shortcode = POST_PATH_RE.match(urlparse(url).path).group(1)
        handle = re.search(r"-\s*([\w.]+) on ", meta("description"))
        display = re.match(r"(.+?) on Instagram", meta("title"))
        caption = re.search(r':\s*"(.*)"\s*$', meta("title"), re.S)
        lines = [line.strip() for line in (caption.group(1) if caption else "").splitlines() if line.strip()]

        post = new_post(
            id=shortcode,
            title=lines[0][:TITLE_LIMIT] if lines else f"Instagram {shortcode}",
            author=(handle.group(1) if handle else display.group(1) if display else ""),
        )
        post["_images"] = [[image]]
        post["_cover"] = [image]
        return post
