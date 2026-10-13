import re
from urllib.parse import quote, urlparse

EXT_BY_MIME = {
    "video/mp4": "mp4",
    "video/webm": "webm",
    "video/quicktime": "mov",
    "video/x-matroska": "mkv",
    "image/png": "png",
    "image/webp": "webp",
    "image/jpeg": "jpg",
    "image/gif": "gif",
    "image/avif": "avif",
}

MIME_BY_EXT = {
    ".webp": "image/webp",
    ".avif": "image/avif",
    ".png": "image/png",
    ".gif": "image/gif",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webm": "video/webm",
    ".mov": "video/quicktime",
    ".mp4": "video/mp4",
    ".m4s": "video/mp4",
    ".mkv": "video/x-matroska",
}

AVIF_BRANDS = (b"avif", b"avis")
HEIF_BRANDS = (b"heic", b"heix", b"hevc", b"hevx", b"heim", b"heis", b"mif1", b"msf1")


def sniff_image(data):
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if data[4:8] == b"ftyp":
        brand = data[8:12]
        if brand in AVIF_BRANDS:
            return "image/avif"
        if brand in HEIF_BRANDS:
            return "image/heic"
    return None


def infer_content_type(upstream, url):
    content_type = (upstream or "").split(";")[0].strip()
    if content_type and content_type != "application/octet-stream":
        return content_type
    path = urlparse(url).path.lower()
    for ext, mime in MIME_BY_EXT.items():
        if path.endswith(ext):
            return mime
    return "video/mp4" if "video" in url else "image/jpeg"


def extension_for(content_type):
    default = "mp4" if content_type.startswith("video") else "jpg"
    return EXT_BY_MIME.get(content_type, default)


def safe_filename(name):
    cleaned = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "", name or "").strip()[:120]
    return cleaned or "media"


def content_disposition(name, ext, inline):
    disposition = "inline" if inline else "attachment"
    full = f"{name}.{ext}"
    try:
        full.encode("ascii")
    except UnicodeEncodeError:
        return f"{disposition}; filename*=UTF-8''{quote(full)}"
    return f'{disposition}; filename="{full}"'
