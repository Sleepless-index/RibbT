import json
import subprocess
from urllib.parse import urlparse

import requests

from ..services import ytdlp_runner
from ..services.candidates import candidate, codec_from_ytdlp, image_candidates
from ..services.net import host_ok
from .base import PlatformAdapter, new_post, video_group
from .ytdlp import (
    best_audio_url,
    dump_playlist,
    has_stream as _has_stream,
    playlist_payload,
    subtitle_tracks,
)

TITLE_LIMIT = 80
YTDLP_TIMEOUT_SECONDS = 90
SHORT_LINK_HOSTS = ("b23.tv",)
PLAYLIST_PATH_MARKERS = ("/favlist", "/medialist", "/lists", "/channel")


class BilibiliAdapter(PlatformAdapter):
    name = "bilibili"
    label = "Bilibili"
    hosts = ("bilibili.com", "m.bilibili.com", "b23.tv")
    display_hosts = ("bilibili.com", "m.bilibili.com", "b23.tv")
    subtitles = True

    def is_playlist(self, url):
        parsed = urlparse(url)
        if (parsed.hostname or "").lower() != "space.bilibili.com":
            return False
        return any(marker in parsed.path for marker in PLAYLIST_PATH_MARKERS)

    def extract_playlist(self, url):
        info = dump_playlist(url, "Bilibili")
        payload = playlist_payload(info)
        if not payload["entries"]:
            raise ValueError("That Bilibili list has no videos you can download.")
        return payload

    def extract(self, url):
        if host_ok(url, SHORT_LINK_HOSTS):
            try:
                url = self.fetch_page(url)[0]
            except (ValueError, requests.RequestException):
                pass

        info = self._dump_info(url)
        post = new_post(
            id=str(info.get("id") or ""),
            title=(info.get("title") or "")[:TITLE_LIMIT],
            author=info.get("uploader", ""),
            avatar=info.get("uploader_thumbnail") or info.get("uploader_avatar") or "",
            kind="video",
        )

        formats = info.get("formats") or []
        audio_url = best_audio_url(formats)
        pool = sorted(
            (f for f in formats if _has_stream(f.get("vcodec")) and f.get("url")),
            key=lambda f: (f.get("height") or 0, f.get("tbr") or 0),
            reverse=True,
        )

        candidates = []
        for f in pool:
            entry = candidate(
                f["url"],
                src=f.get("format_note") or None,
                codec=codec_from_ytdlp(f.get("vcodec")),
                height=f.get("height"),
            )
            if audio_url and not _has_stream(f.get("acodec")):
                entry["audio"] = audio_url
            candidates.append(entry)
        if not candidates:
            raise ValueError("No downloadable stream found for that Bilibili video.")
        post["_videos"].append(video_group(candidates))
        post["subtitles"] = subtitle_tracks(info)

        if info.get("thumbnail"):
            post["_cover"] = image_candidates({"urlDefault": info["thumbnail"]})
        return post

    @staticmethod
    def _dump_info(url):
        try:
            result = ytdlp_runner.run(
                ["--dump-json", "--no-download", "--playlist-items", "1", url],
                YTDLP_TIMEOUT_SECONDS,
            )
        except FileNotFoundError:
            raise ValueError(
                "Bilibili support requires yt-dlp. Install it with: pip install yt-dlp"
            )
        except subprocess.TimeoutExpired:
            raise ValueError("Bilibili timed out. Try again or use a shorter video.")

        if result.returncode != 0:
            raise ValueError("Could not parse that Bilibili video.")
        try:
            return json.loads(result.stdout.strip().splitlines()[0])
        except (json.JSONDecodeError, IndexError):
            raise ValueError("Could not parse the Bilibili response.")
