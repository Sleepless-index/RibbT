import contextlib
import logging
import os
import subprocess
import tempfile

from flask import Response, send_file, stream_with_context
from urllib3.exceptions import HTTPError

from ..config import REDIRECT_CODES, STREAM_CHUNK_BYTES, TRANSCODE_TIMEOUT_SECONDS
from ..errors import MediaError, blocked_media_url
from . import ffmpeg
from .mediatypes import content_disposition, extension_for, infer_content_type, sniff_image
from .net import open_media
from .transforms import IMAGE_TRANSFORMS

log = logging.getLogger(__name__)


def _download_headers(name, transform, inline=False):
    return {
        "Content-Disposition": content_disposition(name, transform.ext, inline),
        "Cache-Control": "no-store",
    }


def _sources(url, audio_url):
    return [url, audio_url] if audio_url else [url]


def _remove(path):
    with contextlib.suppress(OSError):
        os.unlink(path)


def _relay(process, first):
    yield first
    try:
        while True:
            chunk = process.stdout.read1(STREAM_CHUNK_BYTES)
            if not chunk:
                return
            yield chunk
    except (OSError, ValueError):
        return


def stream_ffmpeg(url, name, transform, audio_url=None, inline=False):
    slot = ffmpeg.acquire_slot(preview=inline)
    process = None
    try:
        process = ffmpeg.spawn(_sources(url, audio_url), transform.args, transform.rw_timeout)
        stderr = ffmpeg.StderrTail(process)
        try:
            first = process.stdout.read1(STREAM_CHUNK_BYTES)
        except (OSError, ValueError):
            first = b""
        if not first:
            with contextlib.suppress(subprocess.TimeoutExpired):
                process.wait(timeout=3)
            stderr.join()
            raise MediaError("Transcode failed:\n" + stderr.text(), 502)

        response = Response(
            stream_with_context(_relay(process, first)),
            mimetype=transform.mime,
            headers=_download_headers(name, transform, inline),
        )
    except BaseException:
        if process is not None:
            ffmpeg.stop(process)
        slot.release()
        raise

    def finish():
        ffmpeg.stop(process)
        slot.release()

    response.call_on_close(finish)
    return response


def transcode_to_file(url, name, transform, audio_url=None):
    slot = ffmpeg.acquire_slot()
    handle, path = tempfile.mkstemp(prefix="mdl_", suffix="." + transform.ext)
    os.close(handle)
    process = None
    try:
        process = ffmpeg.spawn(
            _sources(url, audio_url),
            [*transform.args, path],
            transform.rw_timeout,
            stdout=subprocess.DEVNULL,
        )
        stderr = ffmpeg.StderrTail(process)
        try:
            process.wait(timeout=TRANSCODE_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            ffmpeg.stop(process)
            raise MediaError(
                f"Transcode timed out after {TRANSCODE_TIMEOUT_SECONDS // 60} minutes.\n"
                + stderr.text(),
                504,
            )
        stderr.join()
        ffmpeg.stop(process)
        slot.release()

        if process.returncode != 0 or not os.path.exists(path):
            raise MediaError(
                f"Transcode failed (ffmpeg exited {process.returncode}).\n" + stderr.text(),
                502,
            )
        if os.path.getsize(path) == 0:
            raise MediaError("Transcode produced an empty file.\n" + stderr.text(), 502)

        response = send_file(path, mimetype=transform.mime, conditional=False, etag=False)
        response.headers.update(_download_headers(name, transform))
        response.call_on_close(lambda: _remove(path))
        return response
    except BaseException:
        if process is not None:
            ffmpeg.stop(process)
        slot.release()
        _remove(path)
        raise


def passthrough(url, name, inline, range_header=None):
    upstream = open_media(url, {"Range": range_header} if range_header else {}, {})
    if upstream is None:
        raise blocked_media_url()
    if upstream.status_code in REDIRECT_CODES:
        upstream.close()
        raise MediaError("Upstream redirect could not be followed", 502)

    content_type = infer_content_type(upstream.headers.get("Content-Type", ""), url)
    chunks = upstream.raw.stream(STREAM_CHUNK_BYTES, decode_content=False)

    first = b""
    if content_type.startswith("image/") and upstream.status_code == 200 and not range_header:
        try:
            first = next(chunks, b"")
        except (HTTPError, OSError):
            first = b""
        sniffed = sniff_image(first)
        if sniffed == "image/heic" and ffmpeg.FFMPEG:
            upstream.close()
            return stream_ffmpeg(url, name, IMAGE_TRANSFORMS["jpg"])
        if sniffed:
            content_type = sniffed

    cacheable = inline and upstream.status_code < 400
    headers = {
        "Content-Type": content_type,
        "Accept-Ranges": "bytes",
        "Content-Disposition": content_disposition(name, extension_for(content_type), inline),
        "Cache-Control": "private, max-age=3600" if cacheable else "no-store",
    }
    for header in ("Content-Length", "Content-Range", "Content-Encoding"):
        if upstream.headers.get(header):
            headers[header] = upstream.headers[header]

    def body():
        try:
            if first:
                yield first
            yield from chunks
        except (HTTPError, OSError):
            return

    response = Response(
        stream_with_context(body()), status=upstream.status_code, headers=headers
    )
    response.call_on_close(upstream.close)
    return response
