import json
import re
from urllib.parse import urlparse

import requests

from ..config import DESKTOP_PAGE_HEADERS
from ..services.candidates import (
    candidate,
    clean_url,
    dedupe_by_url,
    height_from_url,
    image_candidates,
    stream_urls,
)
from ..services.net import host_ok
from .base import PlatformAdapter, new_post, video_group

NOTE_ID_RE = re.compile(
    r"/(?:explore|discovery/item|user/profile/[0-9a-f]+|note)/([0-9a-f]{24})",
    re.I,
)
STATE_RE = re.compile(r"window\.__INITIAL_STATE__\s*=\s*")
OG_VIDEO_RE = re.compile(r'<meta[^>]+(?:name|property)="og:video"[^>]+content="([^"]+)"')
OG_IMAGE_RE = re.compile(r'<meta[^>]+(?:name|property)="og:image"[^>]+content="([^"]+)"')
CANONICAL_HOSTS = ("xiaohongshu.com", "rednote.com")
MAX_PAGES_TRIED = 4
ORIGINAL = "original"


def is_original(candidate_):
    return (candidate_.get("src") or "").lower() == ORIGINAL


class RedNoteAdapter(PlatformAdapter):
    name = "rednote"
    label = "RedNote"
    features = ('Video', 'Photos')
    hosts = ("xiaohongshu.com", "xhslink.com", "xhslink.cn", "rednote.com", "xhs.cn")
    display_hosts = ("rednote.com", "xiaohongshu.com", "xhslink.com")

    def rank(self, candidates, prefs):
        originals = [c for c in candidates if is_original(c)]
        others = [c for c in candidates if not is_original(c)]
        return originals + others

    def extract(self, url):
        data, final_url = self._load_first_usable_page(url)

        needs_original = (
            data
            and data["kind"] == "video"
            and data["_videos"]
            and not any(is_original(c) for c in data["_videos"][0]["cands"])
        )
        if needs_original:
            self._add_original_from_desktop(data, final_url)
        if data and data.get("_live_missing"):
            self._add_live_clips_from_desktop(data, final_url)
        if data:
            data.pop("_live_missing", None)
            data["_videos"].sort(key=lambda group: group.get("live_index", 0))

        if not data or not (data["_videos"] or data["_images"]):
            raise ValueError(
                "No media found. The post may be private or deleted, or "
                "RedNote blocked the request. Try the share link from the "
                "app (xhslink.com)."
            )
        return data

    def _load_first_usable_page(self, url):
        tried, queue, data = set(), [url], None
        final_url = url
        while queue and len(tried) < MAX_PAGES_TRIED:
            current = queue.pop(0)
            if current in tried:
                continue
            tried.add(current)
            final_url, html = self.fetch_page(current)
            tried.add(final_url)
            data = extract_media(html)
            if data["_videos"] or data["_images"]:
                break
            queue.extend(
                a for a in alternates(final_url) + alternates(current) if a not in tried
            )
        return data, final_url

    def _add_original_from_desktop(self, data, page_url):
        try:
            _, html = self.fetch_page(page_url, DESKTOP_PAGE_HEADERS)
            extra = extract_media(html)
        except (ValueError, requests.RequestException):
            return
        originals = [c for group in extra["_videos"] for c in group["cands"] if is_original(c)]
        if originals and data["_videos"]:
            first = data["_videos"][0]
            first["cands"] = originals[:1] + [
                c for c in first["cands"] if c["url"] != originals[0]["url"]
            ]

    def _add_live_clips_from_desktop(self, data, page_url):
        try:
            _, html = self.fetch_page(page_url, DESKTOP_PAGE_HEADERS)
        except (ValueError, requests.RequestException):
            return
        state = parse_state(html)
        note = pick_note(state) if state else None
        if not note:
            return
        clips = live_clips_by_index(note)
        for index in data["_live_missing"]:
            if index in clips:
                data["_videos"].append(live_group(index, clips[index]))
        data["_live_missing"] = [i for i in data["_live_missing"] if i not in clips]


def find_notes(obj):
    if isinstance(obj, dict):
        looks_like_note = ("imageList" in obj or "video" in obj) and (
            "title" in obj or "desc" in obj or "noteId" in obj or "type" in obj
        )
        if looks_like_note:
            yield obj
        for value in obj.values():
            yield from find_notes(value)
    elif isinstance(obj, list):
        for value in obj:
            yield from find_notes(value)


def balanced_json(html, start):
    if start >= len(html) or html[start] != "{":
        return None
    depth = 0
    in_string = False
    escaped = False
    for index in range(start, len(html)):
        char = html[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                raw = re.sub(r"\bundefined\b", "null", html[start:index + 1])
                try:
                    return json.loads(raw)
                except json.JSONDecodeError:
                    return None
    return None


def parse_state(html):
    match = STATE_RE.search(html)
    if not match:
        return None
    start = match.end()
    while start < len(html) and html[start].isspace():
        start += 1
    return balanced_json(html, start)


def pick_note(state):
    note_state = state.get("note") or {}
    current = note_state.get("currentNoteId")
    note = ((note_state.get("noteDetailMap") or {}).get(current) or {}).get("note")
    if not note:
        note = ((state.get("noteData") or {}).get("data") or {}).get("noteData")
    if not note:
        note = next(iter(find_notes(state)), None)
    return note


def video_candidates(note):
    video = note.get("video") or {}
    candidates = []
    key = (video.get("consumer") or {}).get("originVideoKey")
    if key:
        original_url = f"https://sns-video-bd.xhscdn.com/{key}"
        candidates.append(
            candidate(
                original_url,
                src=ORIGINAL,
                codec="h264",
                height=height_from_url(original_url),
            )
        )
    candidates += stream_urls((video.get("media") or {}).get("stream"))
    return dedupe_by_url(candidates)


def _post_from_note(note):
    user = note.get("user") or {}
    post = new_post(
        id=note.get("noteId") or note.get("id") or "",
        title=(note.get("title") or (note.get("desc") or "")[:80]).strip(),
        author=user.get("nickname") or user.get("nickName") or "",
        avatar=clean_url(
            user.get("avatar") or user.get("image") or user.get("avatarUrl") or ""
        )
        or "",
    )

    images = [c for c in map(image_candidates, note.get("imageList") or []) if c]
    video_cands = video_candidates(note)

    if video_cands or note.get("type") == "video":
        post["kind"] = "video"
        if video_cands:
            streams = stream_urls(((note.get("video") or {}).get("media") or {}).get("stream"))
            post["_videos"].append(
                video_group(video_cands, preview=streams[0]["url"] if streams else None)
            )
        post["_cover"] = images[0] if images else None
        return post

    post["_images"] = images
    post["_cover"] = images[0] if images else None
    clips = live_clips_by_index(note)
    for index, cands in clips.items():
        post["_videos"].append(live_group(index, cands))
    post["_live_missing"] = [
        index
        for index, image in enumerate(note.get("imageList") or [])
        if is_live_flagged(image) and index not in clips
    ]
    return post


def is_live_flagged(image):
    value = image.get("livePhoto")
    return value is True or str(value).lower() in ("1", "true")


def live_clips_by_index(note):
    clips = {}
    for index, image in enumerate(note.get("imageList") or []):
        if not isinstance(image, dict):
            continue
        found = stream_urls(image.get("stream"))
        if found:
            clips[index] = found
    return clips


def live_group(index, cands):
    return video_group(
        cands[:1],
        label=f"Live photo {index + 1}",
        live=True,
        live_index=index + 1,
    )


def _post_from_meta_tags(html, post):
    videos = [u for u in map(clean_url, OG_VIDEO_RE.findall(html)) if u]
    images = [u for u in map(clean_url, OG_IMAGE_RE.findall(html)) if u]

    if videos:
        post["kind"] = "video"
        post["_videos"] = [
            video_group([candidate(videos[0], height=height_from_url(videos[0]))])
        ]
        post["_cover"] = image_candidates({"urlDefault": images[0]}) if images else None
    else:
        post["_images"] = [image_candidates({"urlDefault": u}) for u in images]
        post["_cover"] = post["_images"][0] if post["_images"] else None

    if not post["title"]:
        title = re.search(r"<title>(.*?)</title>", html, re.S)
        post["title"] = title.group(1).strip() if title else ""
    return post


def extract_media(html):
    state = parse_state(html)
    note = pick_note(state) if state else None
    post = _post_from_note(note) if note else new_post()

    if not post["_videos"] and not post["_images"]:
        post = _post_from_meta_tags(html, post)
    return post


def alternates(url):
    match = NOTE_ID_RE.search(url)
    if not match or not host_ok(url, CANONICAL_HOSTS):
        return []
    query = urlparse(url).query
    suffix = f"?{query}" if query else ""
    note_id = match.group(1)
    return [
        f"https://www.xiaohongshu.com/discovery/item/{note_id}{suffix}",
        f"https://www.xiaohongshu.com/explore/{note_id}{suffix}",
    ]
