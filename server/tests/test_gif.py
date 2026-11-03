import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from mediadl import create_app  # noqa: E402
from mediadl.adapters import pick_adapter  # noqa: E402
from mediadl.services.appview import build_app_post  # noqa: E402
from mediadl.services.transforms import STREAM_TRANSFORMS  # noqa: E402


def _prefs(gif):
    return {"metadata": {"filename_style": "classic"}, "audio": {"tiktok_original": False}, "images": {"gif_as_gif": gif}}


def _post(loop):
    return {
        "id": "1", "title": "T", "author": "A", "kind": "video", "cover": None,
        "videos": [{"label": "Video", "url": "https://i.imgur.com/abc.mp4", "loop": loop}],
    }


class GifTests(unittest.TestCase):
    def test_loop_video_offers_a_gif_download_with_no_audio_button(self):
        item = build_app_post(_post(True), "imgur", _prefs(True), "https://imgur.com/abc")["items"][0]
        self.assertEqual([d["label"] for d in item["downloads"]], ["Download GIF"])
        self.assertIn("loop=1", item["downloads"][0]["url"])

    def test_gif_setting_off_keeps_the_video(self):
        item = build_app_post(_post(True), "imgur", _prefs(False), "https://imgur.com/abc")["items"][0]
        self.assertEqual(item["downloads"][0]["label"], "Download")
        self.assertEqual(len(item["downloads"]), 2)

    def test_plain_video_is_not_turned_into_a_gif(self):
        item = build_app_post(_post(False), "imgur", _prefs(True), "https://imgur.com/abc")["items"][0]
        self.assertEqual(item["downloads"][0]["label"], "Download")

    def test_gifv_link_is_flagged_as_a_loop(self):
        post = pick_adapter("https://i.imgur.com/abc1234.gifv").extract("https://i.imgur.com/abc1234.gifv")
        self.assertTrue(post["_videos"][0]["loop"])

    def test_video_route_streams_a_gif_for_any_site_when_flagged(self):
        app = create_app()
        client = app.test_client()
        with mock.patch("mediadl.routes.media.require_media_url", side_effect=lambda u: u), \
                mock.patch("mediadl.routes.media.stream_ffmpeg", return_value=("gif", 200)) as stream:
            client.get("/api/video", query_string={"u": "https://i.imgur.com/abc.mp4", "name": "x", "loop": "1"})
        self.assertTrue(stream.called)
        self.assertIs(stream.call_args.args[2], STREAM_TRANSFORMS["gif"])


if __name__ == "__main__":
    unittest.main()
