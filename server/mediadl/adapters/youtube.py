import re
from urllib.parse import parse_qs, urlparse

from ..services.candidates import image_candidates
from ..services.ranking import CODEC_PRIORITY, UNKNOWN_CODEC_RANK, quality_score, quality_target
from .base import PlatformAdapter, new_post, video_group
from .ytdlp import (
    best_audio_url,
    dump_info,
    dump_playlist,
    playlist_payload,
    subtitle_tracks,
    video_candidates,
)

TITLE_LIMIT = 80
VIDEO_ID_RE = re.compile(r"^[\w-]{11}$")
WATCH_BASE = "https://www.youtube.com/watch?v="
THUMB_BASE = "https://i.ytimg.com/vi/{id}/mqdefault.jpg"
MIX_PREFIX = "RD"


def _query(url):
    return parse_qs(urlparse(url).query)


def _list_id(url):
    values = _query(url).get("list") or []
    return values[0] if values else ""


class YouTubeAdapter(PlatformAdapter):
    name = "youtube"
    label = "YouTube"
    hosts = ("youtube.com", "youtu.be")
    subtitles = True

    def has_playlist(self, url):
        return bool(_list_id(url))

    def is_playlist(self, url):
        if not _list_id(url):
            return False
        parsed = urlparse(url)
        if (parsed.hostname or "").lower().endswith("youtu.be"):
            return False
        if parsed.path.rstrip("/") == "/playlist":
            return True
        return not _query(url).get("v")

    def extract_playlist(self, url):
        list_id = _list_id(url)
        video_id = (_query(url).get("v") or [""])[0]
        if list_id.startswith(MIX_PREFIX) and video_id:
            target = f"{WATCH_BASE}{video_id}&list={list_id}"
        else:
            target = f"https://www.youtube.com/playlist?list={list_id}"
        info = dump_playlist(target, "YouTube")
        payload = playlist_payload(info, WATCH_BASE, THUMB_BASE)
        if not payload["entries"]:
            raise ValueError("That YouTube playlist has no videos you can download.")
        return payload

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
        post["subtitles"] = subtitle_tracks(info)

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
