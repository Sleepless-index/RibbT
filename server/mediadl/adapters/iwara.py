import hashlib
import re
from urllib.parse import parse_qs, urlparse

import requests

from ..config import DESKTOP_UA
from ..services.candidates import candidate, image_candidates
from ..services.net import get_session
from .base import PlatformAdapter, new_post, video_group

TITLE_LIMIT = 80
VIDEO_RE = re.compile(r"^/videos?/([A-Za-z0-9]+)", re.I)
API = "https://apiq.iwara.tv"
# The site's own front end sends these with every call; without them the API answers with an empty page.
API_HEADERS = {"User-Agent": DESKTOP_UA, "Origin": "https://www.iwara.tv", "Referer": "https://www.iwara.tv/"}
# Part of the key the site's front end signs the file-list request with.
VERSION_SALT = "mSvL05GfEmeEmsEYfGCnVpEjYgTJraJN"
# Iwara also lists a tiny "preview" clip next to the real sizes; it is never what anyone wants.
SKIPPED_FORMATS = ("preview",)


def _get_json(url, headers, what):
    try:
        response = get_session().get(url, headers=headers, timeout=20)
    except requests.RequestException:
        raise ValueError(f"Could not reach Iwara ({what}). Try again.")
    try:
        data = response.json()
    except ValueError:
        raise ValueError(f"Iwara didn't answer ({what}, status {response.status_code}). It may be blocking the request.")
    return data


def _version_header(file_url):
    parsed = urlparse(file_url)
    expires = (parse_qs(parsed.query).get("expires") or [""])[0]
    last = parsed.path.rstrip("/").split("/")[-1]
    return hashlib.sha1("_".join((last, expires, VERSION_SALT)).encode()).hexdigest()


def _absolute(url):
    return ("https:" + url) if url.startswith("//") else url


class IwaraAdapter(PlatformAdapter):
    name = "iwara"
    label = "Iwara"
    features = ("Video",)
    hosts = ("iwara.tv",)
    note = "Private videos aren't supported."

    def rank(self, candidates, prefs):
        ranked = super().rank(candidates, prefs)
        # "Source" is the original upload and has no height, so a plain ranking would put it last.
        if (((prefs or {}).get("video") or {}).get("quality", "best")) == "best":
            ranked.sort(key=lambda c: c.get("src") != "Source")
        return ranked

    def extract(self, url):
        match = VIDEO_RE.match(urlparse(url).path)
        if not match:
            raise ValueError("That doesn't look like an Iwara video link.")
        video_id = match.group(1)

        video = _get_json(f"{API}/video/{video_id}", API_HEADERS, "video info")
        if not isinstance(video, dict) or video.get("message"):
            message = str((video or {}).get("message") or "")
            if "private" in message.lower():
                raise ValueError("That Iwara video is private.")
            raise ValueError("Iwara says that video isn't available." if message else "Could not read that Iwara video.")
        file_url = video.get("fileUrl")
        if not file_url:
            raise ValueError("That Iwara video is hosted somewhere else and can't be downloaded from here.")

        listing = _get_json(file_url, {**API_HEADERS, "X-Version": _version_header(file_url)}, "file list")
        if not isinstance(listing, list):
            raise ValueError("Iwara didn't give a file list for that video.")

        formats = []
        for entry in listing:
            name = str(entry.get("name") or "")
            src = entry.get("src") or {}
            link = _absolute(src.get("view") or src.get("download") or "")
            if link and name.lower() not in SKIPPED_FORMATS:
                formats.append((name, link))
        # Source first (the original), then the numbered sizes from the biggest down.
        formats.sort(key=lambda f: (f[0] != "Source", -(int(f[0]) if f[0].isdigit() else 0)))
        candidates = [
            candidate(link, src=name, codec=None, height=int(name) if name.isdigit() else None, size=None)
            for name, link in formats
        ]
        if not candidates:
            raise ValueError("No downloadable stream found for that Iwara video.")

        post = new_post(
            id=video_id,
            title=(video.get("title") or "")[:TITLE_LIMIT],
            author=((video.get("user") or {}).get("name") or (video.get("user") or {}).get("username") or ""),
            kind="video",
        )
        post["_videos"].append(video_group(candidates))
        file_id = (video.get("file") or {}).get("id")
        thumbnail = video.get("thumbnail")
        if file_id and isinstance(thumbnail, int):
            post["_cover"] = image_candidates(
                {"urlDefault": f"https://files.iwara.tv/image/thumbnail/{file_id}/thumbnail-{thumbnail:02d}.jpg"}
            )
        return post
