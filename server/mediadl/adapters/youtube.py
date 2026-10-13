import re

from ..services.candidates import image_candidates
from ..services.ranking import CODEC_PRIORITY, UNKNOWN_CODEC_RANK, quality_score, quality_target
from .base import PlatformAdapter, new_post, video_group
from .ytdlp import best_audio_url, dump_info, video_candidates

TITLE_LIMIT = 80
VIDEO_ID_RE = re.compile(r"^[\w-]{11}$")


class YouTubeAdapter(PlatformAdapter):
    name = "youtube"
    label = "YouTube"
    hosts = ("youtube.com", "youtu.be")

    def extract(self, url):
        info = self._dump_info(url)
        formats = info.get("formats") or []
        candidates = video_candidates(formats, best_audio_url(formats))
        if not candidates:
            raise ValueError("No downloadable stream found for that YouTube video.")

        video_id = str(info.get("id") or "")
        post = new_post(
            id=video_id,
            title=(info.get("title") or "")[:TITLE_LIMIT],
            author=info.get("uploader") or info.get("channel") or "",
            kind="video",
        )
        post["_videos"].append(video_group(candidates))

        covers = []
        if VIDEO_ID_RE.match(video_id):
            covers.append(f"https://i.ytimg.com/vi/{video_id}/maxresdefault.jpg")
        if info.get("thumbnail"):
            covers.append(info["thumbnail"])
        post["_cover"] = [c for c in covers if c] or None
        return post

    def rank(self, candidates, prefs):
        # H.264 stops at 1080p on YouTube, so a codec-first sort would never reach 1440p/4K.
        # Quality decides first and codec only breaks ties.
        target = quality_target(((prefs or {}).get("video") or {}).get("quality", "best"))
        return sorted(
            candidates,
            key=lambda c: (
                quality_score(c.get("height"), target),
                CODEC_PRIORITY.get(c.get("codec"), UNKNOWN_CODEC_RANK),
            ),
        )

    @staticmethod
    def _dump_info(url):
        return dump_info(url, "YouTube", ("--no-playlist",))
