import re
from dataclasses import dataclass

DEFAULT_RW_TIMEOUT = 30_000_000
STREAM_RW_TIMEOUT = 60_000_000
VIDEO_RW_TIMEOUT = 120_000_000


@dataclass(frozen=True)
class Transform:
    args: tuple
    ext: str
    mime: str
    rw_timeout: int = DEFAULT_RW_TIMEOUT


STREAM_TRANSFORMS = {
    "mkv": Transform(
        args=("-c", "copy", "-f", "matroska", "-"),
        ext="mkv",
        mime="video/x-matroska",
        rw_timeout=STREAM_RW_TIMEOUT,
    ),
    "gif": Transform(
        args=(
            "-an",
            "-vf",
            "fps=15,scale=480:-1:flags=lanczos,"
            "split[a][b];[a]palettegen=stats_mode=diff[p];"
            "[b][p]paletteuse=dither=bayer:bayer_scale=5",
            "-loop", "0",
            "-f", "gif",
            "-",
        ),
        ext="gif",
        mime="image/gif",
        rw_timeout=STREAM_RW_TIMEOUT,
    ),
}

IMAGE_TRANSFORMS = {
    "jpg": Transform(
        args=("-f", "image2", "-vcodec", "mjpeg", "-q:v", "2", "-"),
        ext="jpg",
        mime="image/jpeg",
    ),
    "png": Transform(
        args=("-f", "image2", "-vcodec", "png", "-"),
        ext="png",
        mime="image/png",
    ),
    "webp": Transform(
        args=("-f", "image2", "-vcodec", "libwebp", "-q:v", "85", "-"),
        ext="webp",
        mime="image/webp",
    ),
}

_AUDIO_FORMATS = {
    "mp3": ("libmp3lame", "mp3", "mp3", "audio/mpeg"),
    "m4a": ("aac", "mp4", "m4a", "audio/mp4"),
    "opus": ("libopus", "opus", "opus", "audio/ogg"),
}

# Opus in Ogg has no plain "attached picture", so only these two get album art.
_COVER_EXTS = ("mp3", "m4a")

_VIDEO_ENCODE_ARGS = (
    "-c:v", "libx264",
    "-preset", "fast",
    "-pix_fmt", "yuv420p",
    "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
    "-c:a", "aac", "-b:a", "192k",
    "-threads", "0",
)

_VIDEO_CONTAINERS = {
    "mkv": (("-f", "matroska"), "mkv", "video/x-matroska"),
    "mp4": (("-movflags", "+faststart", "-f", "mp4"), "mp4", "video/mp4"),
}


MUX_MAPS = ("-map", "0:v:0", "-map", "1:a:0")

_MUX_CONTAINERS = {
    "mkv": (("-f", "matroska"), "mkv", "video/x-matroska"),
    "mp4": (("-movflags", "+faststart", "-f", "mp4"), "mp4", "video/mp4"),
}


def mux_preview_transform():
    return Transform(
        args=(
            *MUX_MAPS,
            "-c", "copy",
            "-movflags", "frag_keyframe+empty_moov+default_base_moof",
            "-f", "mp4",
            "-",
        ),
        ext="mp4",
        mime="video/mp4",
        rw_timeout=STREAM_RW_TIMEOUT,
    )


def mux_transform(container):
    tail, ext, mime = _MUX_CONTAINERS.get(container) or _MUX_CONTAINERS["mp4"]
    return Transform(args=(*MUX_MAPS, "-c", "copy", *tail), ext=ext, mime=mime, rw_timeout=VIDEO_RW_TIMEOUT)


def audio_transform(fmt, bitrate, tags=None, cover=False):
    """Re-encode to the chosen audio format with tags and, when a second input is the cover, album art."""
    codec, container, ext, mime = _AUDIO_FORMATS.get(fmt) or _AUDIO_FORMATS["mp3"]
    tag_args = []
    for key, value in (tags or {}).items():
        if value:
            tag_args += ["-metadata", f"{key}={value}"]
    cover = bool(cover) and ext in _COVER_EXTS
    picture = (
        "-map", "0:a:0", "-map", "1:v:0",
        "-c:v", "mjpeg", "-q:v", "3", "-pix_fmt", "yuvj420p",
        "-vf", "crop='min(iw,ih)':'min(iw,ih)',scale=600:600",
        "-disposition:v:0", "attached_pic",
        "-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)",
    ) if cover else ("-vn",)
    args = (
        *picture,
        "-c:a", codec,
        "-b:a", f"{bitrate}k",
        "-map_metadata", "0",
        *(("-id3v2_version", "3") if ext == "mp3" else ()),
        *tag_args,
        "-f", container,
    )
    return Transform(args=args, ext=ext, mime=mime)


def audio_supports_cover(fmt):
    return (_AUDIO_FORMATS.get(fmt) or _AUDIO_FORMATS["mp3"])[2] in _COVER_EXTS


def audio_preview_transform():
    """A listenable stream for the in-app player when the source is an HLS playlist."""
    return Transform(
        args=("-vn", "-c:a", "aac", "-b:a", "160k", "-f", "adts", "-"),
        ext="aac",
        mime="audio/aac",
        rw_timeout=STREAM_RW_TIMEOUT,
    )


def video_transform(container, cap_bps, mux=False):
    tail, ext, mime = _VIDEO_CONTAINERS.get(container) or _VIDEO_CONTAINERS["mp4"]
    args = (
        *(MUX_MAPS if mux else ()),
        *_VIDEO_ENCODE_ARGS,
        "-crf", "20",
        "-maxrate", str(cap_bps),
        "-bufsize", str(cap_bps * 2),
        "-map_metadata", "0",
        *tail,
    )
    return Transform(args=args, ext=ext, mime=mime, rw_timeout=VIDEO_RW_TIMEOUT)


def ugoira_transform(container, timing_flag):
    """Frames with their own delays (read from an ffconcat list) into a video that keeps every delay."""
    tail, ext, mime = _VIDEO_CONTAINERS.get(container) or _VIDEO_CONTAINERS["mp4"]
    args = (
        "-an",
        *timing_flag,
        "-c:v", "libx264",
        "-preset", "fast",
        "-pix_fmt", "yuv420p",
        "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
        "-crf", "18",
        "-threads", "0",
        *tail,
    )
    return Transform(args=args, ext=ext, mime=mime, rw_timeout=VIDEO_RW_TIMEOUT)


def bitrate_to_bps(value):
    match = re.fullmatch(r"(\d+)m", value or "")
    return int(match.group(1)) * 1_000_000 if match else None
