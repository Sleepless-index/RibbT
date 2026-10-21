from flask import request

from ..config import MEDIA_HOSTS, SETTINGS_COOKIE
from ..errors import blocked_media_url
from ..preferences import from_cookie, load_defaults
from ..services.mediatypes import safe_filename
from ..services.net import host_ok


def current_preferences():
    return from_cookie(request.cookies.get(SETTINGS_COOKIE)) or load_defaults()


def media_url_arg():
    return request.args.get("u", "")


def filename_arg(default):
    return safe_filename(request.args.get("name", default))


def audio_url_arg():
    value = request.args.get("audio", "")
    return require_media_url(value) if value else None


def require_media_url(url):
    if not url.startswith("https://") or not host_ok(url, MEDIA_HOSTS):
        raise blocked_media_url()
    return url


def audio_tags_arg():
    """Title, artist, album and source link for the file's tags; blank values are dropped."""
    tags = {}
    for key, param in (("title", "title"), ("artist", "artist"), ("album", "album"), ("comment", "source")):
        value = "".join(ch for ch in request.args.get(param, "") if ch.isprintable()).strip()
        if value:
            tags[key] = value[:300]
    return tags
