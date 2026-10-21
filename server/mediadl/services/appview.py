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


def subtitle_path(page, lang, auto, name):
    return _endpoint("/api/subtitle", {"u": page, "lang": lang, "auto": auto, "name": name})


def audio_path(url, name, title=None, artist=None, album=None, source=None):
    return _endpoint(
        "/api/audio",
        {"u": url, "name": name, "title": title, "artist": artist, "album": album, "source": source},
    )


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
        is_live = bool(video.get("live"))
        extra = {
            "index": video.get("live_index") or index,
            "height": video.get("height"),
            "codec": video.get("codec"),
        }
        video_name = name_for("live" if is_live else "video", **extra)
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
            audio_name = name_for("liveaudio" if is_live else "audio", **extra)
            downloads.append({
                "label": "Audio",
                "url": audio_path(
                    video.get("audio_url") or video["url"], audio_name,
                    title=post.get("title"), artist=post.get("author"),
                    album=platform.title() if platform else None, source=link,
                ),
                "name": audio_name,
            })
        suffix = f"_{index}" if len(videos) > 1 else ""
        variants = []
        for variant in video.get("variants") or []:
            variant_name = name_for(
                "live" if is_live else "video",
                **{**extra, "height": variant["height"], "codec": variant.get("codec")},
            )
            variants.append({
                "height": variant["height"],
                "label": f"{variant['height']}p",
                "size": variant.get("size"),
                "name": variant_name,
                "url": video_path(variant["url"], variant_name, loop, variant.get("audio_url")),
            })
        items.append({
            "type": "video",
            "label": video["label"] if is_live else (f"Video {index}" if len(videos) > 1 else "Video"),
            "height": video.get("height"),
            "size": video.get("size"),
            "duration": post.get("duration") or None,
            "variants": variants,
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

    subtitles = []
    for track in post.get("subtitles") or []:
        track_name = name_for("subtitle", lang=track["lang"])
        subtitles.append({
            "lang": track["lang"],
            "label": track["label"],
            "auto": bool(track.get("auto")),
            "name": track_name,
            "url": subtitle_path(link, track["lang"], bool(track.get("auto")), track_name),
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
        "subtitles": subtitles,
    }


def build_app_playlist(playlist, platform, link):
    entries = []
    for entry in playlist.get("entries") or []:
        thumb = entry.get("thumb") or ""
        entries.append({
            "id": entry.get("id") or "",
            "title": entry.get("title") or "",
            "author": entry.get("author") or "",
            "duration": entry.get("duration") or "",
            "url": entry["url"],
            "thumb": media_path(thumb, f"{entry.get('id') or 'entry'}_thumb", True) if thumb else None,
        })
    return {
        "type": "playlist",
        "platform": platform,
        "link": link,
        "title": playlist.get("title") or "Playlist",
        "author": playlist.get("author") or "",
        "total": playlist.get("total") or len(entries),
        "entries": entries,
    }


def sanitize_name(text):
    from .filenames import sanitize

    return sanitize(text)
