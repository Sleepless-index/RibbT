import re


def sanitize(text):
    cleaned = re.sub(r'[\\/:*?"<>|\x00-\x1f]+', "", str(text or ""))
    return re.sub(r"\s+", " ", cleaned).strip()[:100]


def _suffix(kind, index, quality, codec, lang=None):
    if kind == "thumbnail":
        return "thumbnail"
    if kind == "image":
        return f"image {index or 1}"
    if kind == "subtitle":
        return f"{lang or 'subtitles'} subtitles"
    if kind == "live":
        return f"live photo {index or 1}"
    if kind == "liveaudio":
        return f"live photo {index or 1} audio"
    if kind == "audio":
        return f"{quality} {codec} audio" if quality else f"{codec} audio"
    return f"{quality} {codec}" if quality else codec


def _classic(platform, post_id, kind, index, quality, codec, lang=None):
    parts = [platform or "media", post_id or "media"]
    if kind == "thumbnail":
        parts.append("thumbnail")
    elif kind == "image":
        parts.append(f"p{index or 1}")
    elif kind == "subtitle":
        parts.extend([lang or "subs", "subtitles"])
    elif kind == "live":
        parts.append(f"live{index or 1}")
    elif kind == "liveaudio":
        parts.append(f"live{index or 1}_audio")
    else:
        if quality:
            parts.append(quality)
        parts.append(codec)
        if kind == "audio":
            parts.append("audio")
    return "_".join(parts)


def build_filename(style, *, platform, post_id, title, author, kind, index=1, height=None, codec=None, lang=None):
    """Mirror of the web UI's naming (the server can't know video width, so it uses height only)."""
    clean_title = sanitize(title or "Untitled")
    clean_author = sanitize(author or "")
    codec = codec or "h264"
    quality = f"{height}p" if height else None
    suffix = _suffix(kind, index, quality, codec, lang)

    if style == "title":
        return f"{clean_title} ({suffix})"
    if style == "author_title":
        return f"{clean_author} - {clean_title} ({suffix})" if clean_author else f"{clean_title} ({suffix})"
    return _classic(platform, post_id, kind, index, quality, codec, lang)
