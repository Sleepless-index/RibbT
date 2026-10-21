import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

CLIP_ONE = "http://sns-video-bd.xhscdn.com/stream/1/one.mp4"
CLIP_TWO = "http://sns-video-bd.xhscdn.com/stream/1/two.mp4"
PREFS = {
    "metadata": {"filename_style": "classic"},
    "audio": {"tiktok_original": False},
    "x": {"loop_as_gif": False},
}


def image(token, clip=None, live=True):
    entry = {
        "urlDefault": f"http://sns-webpic-qc.xhscdn.com/202601/{token}!nd_dft_wlteh_webp_3",
        "traceId": token,
        "livePhoto": live,
    }
    if clip:
        entry["stream"] = {
            "h264": [{"masterUrl": clip, "height": 1080, "videoBitrate": 2000}],
            "h265": [{"masterUrl": clip.replace("one", "one265"), "height": 1080}],
        }
    return entry


def page(images):
    note = {
        "noteId": "65f0c0a1000000001200abcd",
        "type": "normal",
        "title": "live",
        "user": {"nickname": "a"},
        "imageList": images,
    }
    state = {"note": {"currentNoteId": note["noteId"], "noteDetailMap": {note["noteId"]: {"note": note}}}}
    return "<script>window.__INITIAL_STATE__=" + json.dumps(state) + "</script>"


def load():
    if "mediadl" not in sys.modules:
        root = tempfile.mkdtemp(prefix="ribbt-live-")
        os.environ.setdefault("RIBBT_TMP_DIR", os.path.join(root, "tmp"))
        os.environ.setdefault("RIBBT_COOKIES_PATH", os.path.join(root, "cookies.txt"))
    from mediadl.adapters import rednote
    from mediadl.services import appview

    return rednote, appview


class LivePhotoExtraction(unittest.TestCase):
    def setUp(self):
        self.rednote, _ = load()
        self.extract_media = self.rednote.extract_media

    def test_clips_are_read_in_photo_order(self):
        post = self.extract_media(page([image("a", CLIP_ONE), image("b"), image("c", CLIP_TWO)]))
        self.assertEqual(post["kind"], "images")
        self.assertEqual(len(post["_images"]), 3)
        labels = [group["label"] for group in post["_videos"]]
        self.assertEqual(labels, ["Live photo 1", "Live photo 3"])
        self.assertTrue(all(group["live"] for group in post["_videos"]))
        self.assertEqual(post["_videos"][0]["cands"][0]["url"], CLIP_ONE.replace("http://", "https://"))
        self.assertEqual(post["_live_missing"], [1])

    def test_missing_clips_are_filled_from_the_desktop_page(self):
        mobile = page([image("a"), image("b")])
        desktop = page([image("a", CLIP_ONE), image("b", CLIP_TWO)])
        pages = iter([("https://www.xiaohongshu.com/explore/65f0c0a1000000001200abcd", mobile),
                      ("https://www.xiaohongshu.com/explore/65f0c0a1000000001200abcd", desktop)])
        adapter = self.rednote.RedNoteAdapter()
        with mock.patch.object(adapter, "fetch_page", side_effect=lambda *a, **k: next(pages)):
            post = adapter.extract("https://www.xiaohongshu.com/explore/65f0c0a1000000001200abcd")
        self.assertEqual([g["label"] for g in post["_videos"]], ["Live photo 1", "Live photo 2"])
        self.assertNotIn("_live_missing", post)

    def test_plain_photos_stay_plain(self):
        adapter = self.rednote.RedNoteAdapter()
        html = page([image("a", live=False), image("b", live=False)])
        with mock.patch.object(adapter, "fetch_page", return_value=("https://www.xiaohongshu.com/explore/x", html)):
            post = adapter.extract("https://www.xiaohongshu.com/explore/65f0c0a1000000001200abcd")
        self.assertEqual(post["_videos"], [])
        self.assertEqual(len(post["_images"]), 2)
        self.assertNotIn("_live_missing", post)


class LivePhotoItems(unittest.TestCase):
    def setUp(self):
        self.build_app_post = load()[1].build_app_post
        self.post = {
            "id": "abc",
            "title": "t",
            "author": "a",
            "kind": "images",
            "cover": "https://sns-img-bd.xhscdn.com/a",
            "images": ["https://sns-img-bd.xhscdn.com/a", "https://sns-img-bd.xhscdn.com/b"],
            "videos": [
                {"url": "https://sns-video-bd.xhscdn.com/x1", "label": "Live photo 1", "live": True, "live_index": 1,
                 "codec": "h264", "height": 1080},
                {"url": "https://sns-video-bd.xhscdn.com/x2", "label": "Live photo 2", "live": True, "live_index": 2,
                 "codec": "h264", "height": 1080},
            ],
        }

    def test_each_clip_gets_its_own_label_and_file_name(self):
        view = self.build_app_post(self.post, "rednote", PREFS, "https://xhslink.com/x")
        videos = [item for item in view["items"] if item["type"] == "video"]
        self.assertEqual([v["label"] for v in videos], ["Live photo 1", "Live photo 2"])
        names = [v["downloads"][0]["name"] for v in videos]
        self.assertEqual(names, ["rednote_abc_live1", "rednote_abc_live2"])
        audio = [v["downloads"][1]["name"] for v in videos]
        self.assertEqual(len(set(audio)), 2)

    def test_regular_video_naming_is_unchanged(self):
        self.post["videos"] = [{"url": "https://sns-video-bd.xhscdn.com/x1", "label": "Video", "codec": "h264",
                                "height": 1080}]
        view = self.build_app_post(self.post, "rednote", PREFS, "https://xhslink.com/x")
        video = next(item for item in view["items"] if item["type"] == "video")
        self.assertEqual(video["label"], "Video")
        self.assertEqual(video["downloads"][0]["name"], "rednote_abc_1080p_h264")


if __name__ == "__main__":
    unittest.main()
