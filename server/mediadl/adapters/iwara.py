import re
from urllib.parse import urlparse

from ..services.candidates import candidate, codec_from_ytdlp, image_candidates
from .base import PlatformAdapter, new_post, video_group
from .ytdlp import clock_text, dump_info

TITLE_LIMIT = 80
VIDEO_RE = re.compile(r"^/videos?/[A-Za-z0-9]+", re.I)
# Iwara lists a tiny "preview" clip next to the real sizes (Source, 540, 360); it is never what anyone wants.
SKIPPED_FORMATS = ("preview",)


class IwaraAdapter(PlatformAdapter):
    name = "iwara"
    label = "Iwara"
    features = ("Video",)
    hosts = ("iwara.tv",)
    note = "Some videos need you to be signed in; import your cookies in Settings → Server."

    def rank(self, candidates, prefs):
        ranked = super().rank(candidates, prefs)
        # "Source" is the original upload and has no height, so a plain ranking would put it last.
        if (((prefs or {}).get("video") or {}).get("quality", "best")) == "best":
            ranked.sort(key=lambda c: c.get("src") != "Source")
        return ranked

    def extract(self, url):
        if not VIDEO_RE.match(urlparse(url).path):
            raise ValueError("That doesn't look like an Iwara video link.")
        info = dump_info(url, "Iwara")

        post = new_post(
            id=str(info.get("id") or ""),
            title=(info.get("title") or "")[:TITLE_LIMIT],
            author=info.get("uploader") or info.get("uploader_id") or "",
            kind="video",
        )
        formats = [
            f for f in (info.get("formats") or [])
            if f.get("url") and f.get("vcodec") != "none"
            and str(f.get("format_id") or "").lower() not in SKIPPED_FORMATS
        ]
        formats.sort(key=lambda f: (f.get("height") or 0, f.get("quality") or 0, f.get("tbr") or 0), reverse=True)
        candidates = [
            candidate(
                f["url"],
                src=f.get("format_id") or None,
                codec=codec_from_ytdlp(f.get("vcodec")),
                height=f.get("height"),
                size=f.get("filesize") or f.get("filesize_approx"),
            )
            for f in formats
        ]
        if not candidates:
            raise ValueError("No downloadable stream found for that Iwara video.")
        post["_videos"].append(video_group(candidates))
        post["duration"] = clock_text(info.get("duration"))
        if info.get("thumbnail"):
            post["_cover"] = image_candidates({"urlDefault": info["thumbnail"]})
        return post
