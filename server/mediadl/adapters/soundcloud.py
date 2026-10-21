from urllib.parse import urlparse

from ..services.candidates import clean_url
from .base import PlatformAdapter, new_post
from .ytdlp import DIRECT_PROTOCOLS, clock_text, dump_info, has_stream

TITLE_LIMIT = 80
# Progressive files first: HLS playlists can't be fetched as a single file.
CODEC_PREFERENCE = ("mp3", "mp4a", "aac", "opus", "vorbis")


def _audio_rank(fmt):
    codec = (fmt.get("acodec") or "").lower()
    order = next((i for i, name in enumerate(CODEC_PREFERENCE) if codec.startswith(name)), len(CODEC_PREFERENCE))
    return (fmt.get("abr") or fmt.get("tbr") or 0, -order)


def audio_stream(info):
    """Best direct audio-only stream of a yt-dlp result, or None."""
    streams = [
        f for f in info.get("formats") or []
        if f.get("url") and not has_stream(f.get("vcodec")) and has_stream(f.get("acodec"))
        and f.get("protocol") in DIRECT_PROTOCOLS
    ]
    return max(streams, key=_audio_rank) if streams else None


def audio_post(info, id_prefix=""):
    """An audio-only post (kind "audio") from a yt-dlp result; raises ValueError without a stream."""
    stream = audio_stream(info)
    if not stream:
        raise ValueError("No downloadable audio stream found for that track.")
    post = new_post(
        id=f"{id_prefix}{info.get('id') or ''}",
        title=(info.get("title") or "")[:TITLE_LIMIT],
        author=info.get("uploader") or info.get("artist") or info.get("channel") or "",
        kind="audio",
    )
    post["audio_track"] = {"url": stream["url"], "size": stream.get("filesize") or stream.get("filesize_approx")}
    post["duration"] = clock_text(info.get("duration"))
    thumbnails = [t.get("url") for t in info.get("thumbnails") or [] if isinstance(t, dict) and t.get("url")]
    covers = [clean_url(u) for u in [info.get("thumbnail"), *reversed(thumbnails)]]
    post["_cover"] = [c for c in covers if c] or None
    return post


class SoundCloudAdapter(PlatformAdapter):
    name = "soundcloud"
    label = "SoundCloud"
    hosts = ("soundcloud.com",)

    def matches(self, url):
        return super().matches(url)

    def extract(self, url):
        if "/sets/" in urlparse(url).path:
            raise ValueError("SoundCloud playlists aren’t supported yet. Paste a single track.")
        info = dump_info(url, "SoundCloud", ("--no-playlist",))
        return audio_post(info)
