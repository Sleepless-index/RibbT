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


def audio_transform(fmt, bitrate):
    codec, container, ext, mime = _AUDIO_FORMATS.get(fmt) or _AUDIO_FORMATS["mp3"]
    args = (
        "-vn",
        "-c:a", codec,
        "-b:a", f"{bitrate}k",
        "-map_metadata", "0",
        "-f", container,
    )
    return Transform(args=args, ext=ext, mime=mime)


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


def bitrate_to_bps(value):
    match = re.fullmatch(r"(\d+)m", value or "")
    return int(match.group(1)) * 1_000_000 if match else None
