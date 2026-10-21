import logging
from urllib.parse import urlparse

from flask import Blueprint, Response, request

from ..adapters import pick_adapter
from ..config import TWITTER_MEDIA_HOSTS
from ..errors import MediaError, blocked_media_url
from ..services.delivery import mux_to_file, passthrough, stream_ffmpeg, transcode_audio, transcode_to_file
from ..services import subtitles as subtitle_service
from ..services.ffmpeg import probe_bitrate
from ..services.mediatypes import content_disposition
from ..services.net import content_type_of, host_ok, resolve_media
from ..services.transforms import (
    IMAGE_TRANSFORMS,
    STREAM_TRANSFORMS,
    audio_preview_transform,
    audio_supports_cover,
    audio_transform,
    bitrate_to_bps,
    mux_preview_transform,
    video_transform,
)
from .common import audio_tags_arg, audio_url_arg, current_preferences, filename_arg, media_url_arg, require_media_url

log = logging.getLogger(__name__)

bp = Blueprint("media", __name__)


def is_twitter_media(url):
    return host_ok(url, TWITTER_MEDIA_HOSTS)


def is_twitter_loop(url):
    return is_twitter_media(url) and "/tweet_video/" in urlparse(url).path


@bp.get("/api/media")
def media():
    url = media_url_arg()
    name = filename_arg("media")
    inline = request.args.get("inline") == "1"
    prefs = current_preferences()

    audio_url = audio_url_arg()
    if audio_url and inline:
        return stream_ffmpeg(url, name, mux_preview_transform(), audio_url=audio_url, inline=True)

    wants_gif = not inline and prefs["x"]["loop_as_gif"] and is_twitter_media(url)
    image_container = prefs["images"]["container"]
    wants_image_convert = not inline and image_container != "auto"

    if wants_gif or wants_image_convert:
        content_type = content_type_of(url) or ""
        if content_type.startswith("video/") and wants_gif:
            return stream_ffmpeg(url, name, STREAM_TRANSFORMS["gif"])
        if content_type.startswith("image/") and wants_image_convert:
            target = IMAGE_TRANSFORMS[image_container]
            if content_type != target.mime:
                return stream_ffmpeg(url, name, target)

    return passthrough(url, name, inline, request.headers.get("Range"))


@bp.get("/api/video")
def video():
    url = require_media_url(media_url_arg())
    name = filename_arg("video")
    prefs = current_preferences()

    if is_twitter_media(url):
        is_loop = is_twitter_loop(url) or request.args.get("loop") == "1"
        if is_loop and prefs["x"]["loop_as_gif"]:
            return stream_ffmpeg(url, name, STREAM_TRANSFORMS["gif"])

    settings = prefs["video"]
    container = settings["container"]
    cap = bitrate_to_bps(settings["bitrate"])
    audio_url = audio_url_arg()
    if audio_url:
        # YouTube-style: picture and sound are separate files. Fetch both, then join them.
        return mux_to_file(url, audio_url, name, container, cap)
    if cap is None:
        return passthrough(url, name, False, request.headers.get("Range"))

    final_url, headers = resolve_media(url)
    if not final_url:
        raise blocked_media_url()

    source_bps = probe_bitrate(final_url, headers)
    if source_bps is not None and source_bps <= cap:
        if container == "mkv":
            return stream_ffmpeg(final_url, name, STREAM_TRANSFORMS["mkv"])
        return passthrough(url, name, False, request.headers.get("Range"))

    return transcode_to_file(final_url, name, video_transform(container, cap))


@bp.get("/api/audio")
def audio():
    url = require_media_url(media_url_arg())
    name = filename_arg("audio")
    settings = current_preferences()["audio"]
    tags = audio_tags_arg()
    cover = request.args.get("cover", "")
    cover = cover if cover and audio_supports_cover(settings["format"]) and _media_url_ok(cover) else None
    hls = request.args.get("hls") == "1" or ".m3u8" in url.split("?", 1)[0]
    return transcode_audio(
        url, name,
        lambda has_cover: audio_transform(settings["format"], settings["bitrate"], tags, has_cover),
        cover_url=cover, hls=hls,
    )


@bp.get("/api/audio-preview")
def audio_preview():
    """Plays a track the app can't stream directly (an HLS playlist)."""
    url = require_media_url(media_url_arg())
    return stream_ffmpeg(url, filename_arg("preview"), audio_preview_transform(), inline=True)


def _media_url_ok(url):
    try:
        require_media_url(url)
        return True
    except Exception:
        return False


@bp.get("/api/subtitle")
def subtitle():
    page = request.args.get("u", "")
    lang = request.args.get("lang", "")
    auto = request.args.get("auto") == "1"
    name = filename_arg("subtitles")
    adapter = pick_adapter(page)
    if not page.startswith("https://") or adapter is None or not adapter.subtitles:
        raise MediaError("Subtitles can’t be downloaded from that link.", 400)

    content, ext = subtitle_service.fetch(page, lang, auto)
    return Response(
        content,
        mimetype=subtitle_service.MIME.get(ext, "application/octet-stream"),
        headers={
            "Content-Disposition": content_disposition(name, ext, False),
            "Cache-Control": "no-store",
        },
    )
