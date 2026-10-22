import re

import requests

from ..config import MOBILE_UA
from ..services.candidates import candidate_from_url, image_candidates
from ..services.net import get_session
from .base import PlatformAdapter, new_post, video_group

STATUS_ID_RE = re.compile(r"/status/(\d+)")
TITLE_LIMIT = 80


def _first_photo_cover(photos):
    return image_candidates({"urlDefault": photos[0]["url"]}) if photos else None


def _photo_candidates(photos):
    return [image_candidates({"urlDefault": photo["url"]}) for photo in photos]


class TwitterAdapter(PlatformAdapter):
    name = "x"
    label = "X/Twitter"
    features = ('Video', 'Photos')
    hosts = ("twitter.com", "x.com", "t.co")

    def extract(self, url):
        match = STATUS_ID_RE.search(url)
        if not match:
            raise ValueError("That doesn't look like a tweet URL.")
        tweet_id = match.group(1)

        sources = (
            (f"https://api.fxtwitter.com/2/status/{tweet_id}", self._parse_fx),
            (f"https://api.vxtwitter.com/i/status/{tweet_id}", self._parse_vx),
        )
        for api_url, parser in sources:
            try:
                response = get_session().get(
                    api_url, timeout=15, headers={"User-Agent": MOBILE_UA}
                )
                if response.ok:
                    data = parser(response.json())
                    if data:
                        return data
            except (requests.RequestException, ValueError, KeyError):
                continue

        raise ValueError(
            "Could not resolve that tweet. It may be private, deleted, "
            "or age-restricted."
        )

    def _parse_fx(self, payload):
        status = payload.get("status") or payload.get("tweet") or {}
        media = status.get("media") or {}
        videos = media.get("videos") or []
        photos = media.get("photos") or []
        author = status.get("author") or {}

        post = new_post(
            id=str(status.get("id") or ""),
            title=(status.get("text") or "")[:TITLE_LIMIT],
            author=author.get("name", ""),
            avatar=author.get("avatar_url") or "",
        )

        if not videos:
            post["_images"] = _photo_candidates(photos)
            post["_cover"] = post["_images"][0] if post["_images"] else None
            return post

        post["kind"] = "video"
        for video in videos:
            candidates = self._fx_candidates(video)
            if candidates:
                post["_videos"].append(
                    video_group(candidates, loop=video.get("type") == "gif")
                )
        post["_cover"] = _first_photo_cover(photos)
        return post

    @staticmethod
    def _fx_candidates(video):
        variants = sorted(
            video.get("variants") or [], key=lambda v: v.get("bitrate", 0), reverse=True
        )
        with_url = [v for v in variants if v.get("url")]
        mp4s = [v for v in with_url if ".mp4" in v["url"].lower()]
        progressive = [v for v in with_url if ".m3u8" not in v["url"].lower()]
        pool = mp4s or progressive or with_url

        candidates = [candidate_from_url(variant["url"]) for variant in pool]
        if not candidates and video.get("url"):
            candidates = [candidate_from_url(video["url"])]
        return candidates

    def _parse_vx(self, payload):
        post = new_post(
            id=str(payload.get("tweetID") or ""),
            title=(payload.get("text") or "")[:TITLE_LIMIT],
            author=payload.get("user_name", ""),
            avatar=payload.get("user_profile_image_url") or "",
        )

        media = payload.get("media_extended") or []
        videos = [m for m in media if m.get("type") in ("video", "gif")]
        photos = [m for m in media if m.get("type") == "image"]

        if not videos:
            post["_images"] = _photo_candidates(photos)
            post["_cover"] = post["_images"][0] if post["_images"] else None
            return post

        post["kind"] = "video"
        candidates = [candidate_from_url(v["url"]) for v in videos if v.get("url")]
        if candidates:
            post["_videos"].append(
                video_group(candidates, loop=all(v.get("type") == "gif" for v in videos))
            )
        post["_cover"] = _first_photo_cover(photos)
        return post
