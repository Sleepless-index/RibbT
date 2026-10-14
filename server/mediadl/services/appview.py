"""Turns a parsed post into ready-to-use items for the native app.

The app only renders this: preview links, download links and file names all come from here,
so naming rules and options can change without rebuilding the app.
"""
from urllib.parse import quote, urlencode, urlparse

from .filenames import build_filename

MEDIA_VERSION = "v6"
TWITTER_HOSTS = ("twimg.com",)


def _endpoint(path, params):
    cleaned = []
    for key, value in params.items():
        if value is None or value is False:
            continue
        cleaned.append((key, "1" if value is True else value))
    return f"{path}?{urlencode(cleaned, quote_via=quote)}"


def media_path(url, name, inline=False, audio=None):
    return _endpoint("/api/media", {"u": url, "name": name, "inline": inline, "audio": audio, "_v": MEDIA_VERSION})


def audio_path(url, name):
    return _endpoint("/api/audio", {"u": url, "name": name})


def video_path(url, name, loop=False, audio=None):
    return _endpoint("/api/video", {"u": url, "name": name, "loop": loop, "audio": audio})


def _is_twitter_loop(url):
    parsed = urlparse(url or "")
    host = parsed.hostname or ""
    return any(host == h or host.endswith("." + h) for h in TWITTER_HOSTS) and "/tweet_video/" in parsed.path


def build_app_post(post, platform, prefs, link):
    post_id = post.get("id") or "media"
    style = prefs["metadata"]["filename_style"]
    original_sound = prefs["audio"]["tiktok_original"]
    loop_as_gif = prefs["x"]["loop_as_gif"]
    cover = post.get("cover")
    videos = post.get("videos") or []
    images = post.get("images") or []

    def name_for(kind, **extra):
        return build_filename(
            style, platform=platform, post_id=post_id, title=post.get("title"),
            author=post.get("author"), kind=kind, **extra,
        )

    items = []
    for index, video in enumerate(videos, start=1):
        extra = {"index": index, "height": video.get("height"), "codec": video.get("codec")}
        video_name = name_for("video", **extra)
        loop = bool(loop_as_gif and (video.get("loop") or _is_twitter_loop(video.get("url"))))
        downloads = [{
            "label": "Download",
            "url": video_path(video["url"], video_name, loop, video.get("audio_url")),
            "name": video_name,
        }]
        if original_sound and platform == "tiktok" and video.get("sound_url"):
            sound_name = sanitize_name(video.get("sound_name")) or video_name
            downloads.append({"label": "Original sound", "url": media_path(video["sound_url"], sound_name), "name": sound_name})
        else:
            audio_name = name_for("audio", **extra)
            downloads.append({
                "label": "Audio",
                "url": audio_path(video.get("audio_url") or video["url"], audio_name),
                "name": audio_name,
            })
        suffix = f"_{index}" if len(videos) > 1 else ""
        items.append({
            "type": "video",
            "label": f"Video {index}" if len(videos) > 1 else "Video",
            "height": video.get("height"),
            "preview": media_path(cover, f"{post_id}_poster", True) if cover else None,
            # The app plays the picture and the sound as two streams and joins them itself, so the
            # preview needs no ffmpeg (and still has sound when ffmpeg isn't available).
            "play": media_path(video["url"], f"{post_id}_video{suffix}", True),
            "play_audio": media_path(video["audio_url"], f"{post_id}_audio{suffix}", True)
            if video.get("audio_url") else None,
            "play_alt": media_path(video["preview"], f"{post_id}_video{suffix}", True)
            if video.get("preview") and video["preview"] != video["url"] else None,
            "downloads": downloads,
        })

    if post.get("kind") == "video" and cover:
        thumb_name = name_for("thumbnail")
        items.append({
            "type": "image",
            "label": "Thumbnail",
            "preview": media_path(cover, f"{post_id}_thumb", True),
            "downloads": [{"label": "Download", "url": media_path(cover, thumb_name), "name": thumb_name}],
        })

    for index, url in enumerate(images, start=1):
        image_name = name_for("image", index=index)
        items.append({
            "type": "image",
            "label": f"Photo {index}" if len(images) > 1 else "Photo",
            "preview": media_path(url, f"{post_id}_p{index}", True),
            "downloads": [{"label": "Download", "url": media_path(url, image_name), "name": image_name}],
        })

    avatar = post.get("avatar")
    return {
        "id": post_id,
        "platform": platform,
        "link": link,
        "title": post.get("title") or "",
        "author": post.get("author") or "",
        "avatar": media_path(avatar, f"{post_id}_avatar", True) if avatar else None,
        "kind": post.get("kind") or ("video" if videos else "photos"),
        "thumb": media_path(cover or (images[0] if images else None), f"{post_id}_hist", True)
        if (cover or images) else None,
        "items": items,
    }


def sanitize_name(text):
    from .filenames import sanitize

    return sanitize(text)
