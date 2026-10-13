from ..config import MOBILE_UA
from ..services.candidates import candidate, height_from_url, image_candidates
from ..services.net import get_session
from .base import PlatformAdapter, new_post, video_group

RESOLVER_URL = "https://www.tikwm.com/api/"
RESOLVER_ORIGIN = "https://www.tikwm.com"
TITLE_LIMIT = 80


def absolute_url(url):
    if not url:
        return url
    return url if url.startswith("http") else RESOLVER_ORIGIN + url


def _as_dict(value, string_key):
    if isinstance(value, str):
        return {string_key: value}
    return value if isinstance(value, dict) else {}


class TikTokAdapter(PlatformAdapter):
    name = "tiktok"
    label = "TikTok"
    hosts = ("tiktok.com",)

    def extract(self, url):
        data = self._fetch(url)
        author = _as_dict(data.get("author"), "nickname")
        music = _as_dict(data.get("music"), "play_url")

        post = new_post(
            id=str(data.get("id") or ""),
            title=str(data.get("title") or "")[:TITLE_LIMIT],
            author=str(author.get("nickname") or ""),
            avatar=absolute_url(author.get("avatar") or "") or "",
            kind="video",
        )

        images = data.get("images") or []
        if images:
            post["kind"] = "images"
            post["_images"] = [image_candidates({"urlDefault": absolute_url(u)}) for u in images]
            post["_cover"] = post["_images"][0]
        else:
            self._add_video(post, data, music)
            cover = data.get("origin_cover") or data.get("cover")
            if cover:
                post["_cover"] = image_candidates({"urlDefault": absolute_url(cover)})

        if not post["_videos"] and not post["_images"]:
            raise ValueError("No media found in that TikTok post.")
        return post

    @staticmethod
    def _add_video(post, data, music):
        source = data.get("hdplay") or data.get("play")
        if not source:
            return
        video_url = absolute_url(source)

        sound_url = absolute_url(music.get("play_url") or "") or None
        if sound_url == video_url:
            sound_url = None
        sound_name = str(music.get("title") or "").strip() or None

        post["_videos"].append(
            video_group(
                [candidate(video_url, codec="h264", height=height_from_url(video_url))],
                sound_url=sound_url,
                sound_name=sound_name,
            )
        )

    @staticmethod
    def _fetch(url):
        response = get_session().post(
            RESOLVER_URL,
            data={"url": url, "hd": 1},
            headers={"User-Agent": MOBILE_UA},
            timeout=25,
        )
        if not response.ok:
            raise ValueError("Could not reach the TikTok resolver.")

        try:
            payload = response.json()
        except ValueError:
            raise ValueError("The TikTok resolver returned an unreadable response.")
        if not isinstance(payload, dict):
            raise ValueError("The TikTok resolver returned an unexpected response.")

        try:
            code = int(payload.get("code") or 0)
        except (TypeError, ValueError):
            code = -1
        if code != 0:
            raise ValueError(payload.get("msg") or "TikTok didn't return media.")

        data = payload.get("data")
        if not isinstance(data, dict):
            raise ValueError(payload.get("msg") or "No media found in that TikTok post.")
        return data
