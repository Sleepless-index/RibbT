from concurrent.futures import ThreadPoolExecutor

from .candidates import dedupe
from .net import probe

PASSTHROUGH_KEYS = ("sound_url", "sound_name", "loop")
IMAGE_PROBE_WORKERS = 6
VIDEO_PROBE_WORKERS = 4


def first_working(candidates, kind, cache):
    if not candidates:
        return None
    if len(candidates) == 1:
        return candidates[0]
    for url in candidates:
        if probe(url, kind, cache):
            return url
    return candidates[0]


def resolve_video(group, cache, adapter, prefs):
    candidates = adapter.rank(group["cands"], prefs)
    if not candidates:
        return None

    if len(candidates) == 1:
        working = candidates
    else:
        with ThreadPoolExecutor(VIDEO_PROBE_WORKERS) as pool:
            results = list(pool.map(lambda c: probe(c["url"], "video", cache), candidates))
        working = [c for c, ok in zip(candidates, results) if ok] or [candidates[0]]

    chosen = working[0]
    fallback = next((c["url"] for c in working[1:] if c["url"] != chosen["url"]), None)
    if not fallback:
        preview = group.get("preview")
        fallback = preview if preview and preview != chosen["url"] else chosen["url"]

    result = {
        "url": chosen["url"],
        "src": chosen["src"],
        "codec": chosen.get("codec"),
        "height": chosen.get("height"),
        "label": group["label"],
        "preview": fallback,
    }
    if chosen.get("audio"):
        result["audio_url"] = chosen["audio"]
    for key in PASSTHROUGH_KEYS:
        if group.get(key):
            result[key] = group[key]
    return result


def finalize(post, adapter, prefs):
    cache = {}
    cover_candidates = post.pop("_cover", None)
    image_candidates = post.pop("_images", None) or []
    video_groups = post.pop("_videos", None) or []

    with ThreadPoolExecutor(IMAGE_PROBE_WORKERS) as pool:
        images = list(pool.map(lambda c: first_working(c, "image", cache), image_candidates))
        cover = first_working(cover_candidates, "image", cache) if cover_candidates else None

    post["images"] = dedupe(images)
    post["cover"] = cover
    resolved = (resolve_video(group, cache, adapter, prefs) for group in video_groups)
    post["videos"] = [video for video in resolved if video]
    return post
