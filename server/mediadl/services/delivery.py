import contextlib
import logging
import os
import subprocess

from flask import Response, send_file, stream_with_context
from urllib3.exceptions import HTTPError

from ..config import REDIRECT_CODES, STREAM_CHUNK_BYTES, TRANSCODE_TIMEOUT_SECONDS
from ..errors import MediaError, blocked_media_url
from . import ffmpeg, progress, storage
from .mediatypes import content_disposition, extension_for, infer_content_type, sniff_image
from .net import download_to_file, open_media
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
        progress.attach(process)
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


def _temp_path(suffix):
    return storage.temp_path(suffix)


def fetch_sources(urls):
    """Download each source into a temp file. On any failure nothing is left behind."""
    paths = []
    try:
        for url in urls:
            path = _temp_path(".src")
            paths.append(path)
            download_to_file(url, path)
            if os.path.getsize(path) == 0:
                raise MediaError("The media server sent an empty file.", 502)
        return paths
    except BaseException:
        for path in paths:
            _remove(path)
        raise


def encode_local(paths, name, transform, network_urls=None):
    """Run ffmpeg over local input files (or, with ``network_urls``, over URLs it reads itself) into a
    temp file and send that. Always cleans ``paths``."""
    out_path = None
    slot = None
    process = None
    try:
        slot = ffmpeg.acquire_slot()
        progress.set_phase("converting")
        # The output is about as big as the inputs (a straight copy) or smaller (a re-encode).
        storage.require_space(sum(os.path.getsize(path) for path in paths))
        out_path = _temp_path("." + transform.ext)
        if network_urls:
            process = ffmpeg.spawn(network_urls, [*transform.args, out_path], transform.rw_timeout, stdout=subprocess.DEVNULL)
        else:
            process = ffmpeg.spawn_local(paths, [*transform.args, out_path], stdout=subprocess.DEVNULL)
        progress.attach(process)
        stderr = ffmpeg.StderrTail(process)
        try:
            process.wait(timeout=TRANSCODE_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            ffmpeg.stop(process)
            raise MediaError(
                f"Conversion timed out after {TRANSCODE_TIMEOUT_SECONDS // 60} minutes.\n" + stderr.text(),
                504,
            )
        stderr.join()
        ffmpeg.stop(process)
        slot.release()
        slot = None
        for path in paths:
            _remove(path)

        if process.returncode != 0 or not os.path.exists(out_path):
            log.warning("ffmpeg exited %s\ncommand: %s\n%s", process.returncode, transform.args, stderr.text(last=30))
            raise MediaError(
                f"Conversion failed (ffmpeg exited {process.returncode}).\n" + stderr.text(),
                502,
            )
        if os.path.getsize(out_path) == 0:
            raise MediaError("Conversion produced an empty file.\n" + stderr.text(), 502)

        job = progress.current()
        if job is not None:
            # A background download keeps the finished file itself instead of sending it anywhere.
            response = Response(status=204, mimetype=transform.mime)
            response.headers.update(_download_headers(name, transform))
            job.adopt(out_path)
            return response

        response = send_file(out_path, mimetype=transform.mime, conditional=False, etag=False)
        response.headers.update(_download_headers(name, transform))
        done = out_path
        response.call_on_close(lambda: _remove(done))
        return response
    except BaseException:
        if process is not None:
            ffmpeg.stop(process)
        if slot is not None:
            slot.release()
        if out_path:
            _remove(out_path)
        for path in paths:
            _remove(path)
        raise


def transcode_to_file(url, name, transform, audio_url=None):
    return encode_local(fetch_sources(_sources(url, audio_url)), name, transform)


def transcode_from_network(url, name, transform):
    """For sources that are not one downloadable file (HLS playlists): ffmpeg reads them itself."""
    return encode_local([], name, transform, network_urls=[url])


def mux_to_file(url, audio_url, name, container, cap_bps):
    """Join a video-only and an audio-only stream. Copies both unless the video is over the cap."""
    from .transforms import mux_transform, video_transform

    paths = fetch_sources([url, audio_url])
    bps = ffmpeg.probe_file_bitrate(paths[0]) if cap_bps is not None else None
    if cap_bps is not None and bps is not None and bps > cap_bps:
        transform = video_transform(container, cap_bps, mux=True)
    else:
        transform = mux_transform(container)
    return encode_local(paths, name, transform)


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
