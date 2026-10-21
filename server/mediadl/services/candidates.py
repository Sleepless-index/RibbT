import re
from urllib.parse import urlparse

from ..config import XHS_IMAGE_HOSTS
from .net import host_ok

CODEC_NAMES = {"h264": "H.264", "h265": "HEVC", "av1": "AV1", "h266": "VVC"}

MIN_HEIGHT = 144
MAX_HEIGHT = 4320


def clean_url(url):
    if not url or not isinstance(url, str):
        return None
    url = url.replace("\\u002F", "/").replace("\\u002f", "/")
    if url.startswith("//"):
        url = "https:" + url
    return re.sub(r"^http://", "https://", url, count=1, flags=re.I)


def dedupe(items):
    seen = set()
    unique = []
    for item in items:
        if item and item not in seen:
            seen.add(item)
            unique.append(item)
    return unique


def dedupe_by_url(candidates):
    seen = set()
    unique = []
    for candidate in candidates:
        if candidate["url"] not in seen:
            seen.add(candidate["url"])
            unique.append(candidate)
    return unique


def token_from_url(url):
    parts = [part for part in urlparse(url).path.split("/") if part]
    if (
        len(parts) >= 3
        and parts[0].isdigit()
        and len(parts[0]) >= 8
        and re.fullmatch(r"[0-9a-fA-F]{16,}", parts[1])
    ):
        parts = parts[2:]
    return "/".join(parts).split("!")[0]


def image_candidates(image):
    raw = [image.get("urlDefault"), image.get("url")]
    raw += [info.get("url") for info in reversed(image.get("infoList") or [])]
    raw = [url for url in map(clean_url, raw) if url]

    tokens = [token_from_url(url) for url in raw if host_ok(url, XHS_IMAGE_HOSTS)]
    tokens.append(image.get("traceId") or image.get("fileId"))

    candidates = []
    for token in dedupe(tokens):
        candidates += [
            f"https://sns-img-bd.xhscdn.com/{token}",
            f"https://ci.xiaohongshu.com/{token}",
        ]
    candidates += [url.split("!")[0] for url in raw if "!" in url]
    candidates += raw
    return dedupe(candidates)


def codec_from_url(url):
    if not url:
        return None
    lowered = url.lower()
    if "av01" in lowered or "av1/" in lowered:
        return "av1"
    if "vp09" in lowered or "/vp9/" in lowered:
        return "vp9"
    if "hev1" in lowered or "hvc1" in lowered or "hevc" in lowered:
        return "h265"
    if "avc1" in lowered or "h264" in lowered:
        return "h264"
    return None


def height_from_url(url):
    if not url:
        return None
    match = re.search(r"/(\d{3,4})x(\d{3,4})/", url)
    if match:
        width, height = int(match.group(1)), int(match.group(2))
        return min(width, height)
    match = re.search(r"/(\d{3,4})/", url)
    if match:
        height = int(match.group(1))
        if MIN_HEIGHT <= height <= MAX_HEIGHT:
            return height
    return None


def codec_from_ytdlp(vcodec):
    value = (vcodec or "").lower()
    if value.startswith("avc1") or "h264" in value:
        return "h264"
    if value.startswith(("hev1", "hvc1")) or "h265" in value or "hevc" in value:
        return "h265"
    if value.startswith(("vp09", "vp9")):
        return "vp9"
    if value.startswith(("av01", "av1")):
        return "av1"
    return None


def candidate(url, src=None, codec=None, height=None, size=None):
    return {"url": url, "src": src, "codec": codec, "height": height, "size": size}


def candidate_from_url(url):
    return candidate(url, codec=codec_from_url(url), height=height_from_url(url))


def stream_urls(streams):
    ranked = []
    for codec, items in (streams or {}).items():
        best = None
        for item in items or []:
            url = clean_url(item.get("masterUrl")) or next(
                (clean_url(b) for b in item.get("backupUrls") or [] if clean_url(b)), None
            )
            if not url:
                continue
            height = item.get("height") or height_from_url(url) or 0
            score = (height, item.get("videoBitrate") or item.get("size") or 0)
            if best is None or score > best[0]:
                best = (score, url, height)
        if best:
            score, url, height = best
            entry = candidate(
                url,
                src=CODEC_NAMES.get(codec, codec.upper()),
                codec=codec,
                height=height or None,
            )
            ranked.append((codec == "h264", score, entry))
    ranked.sort(key=lambda row: (row[0], row[1]), reverse=True)
    return [row[2] for row in ranked]
