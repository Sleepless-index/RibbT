import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from mediadl.adapters import find_link, pick_adapter  # noqa: E402

LINK = "https://www.iwara.tv/video/k2ayoueezfkx6gvq/some-title"
INFO = {
    "id": "k2ayoueezfkx6gvq", "title": "A video", "uploader": "Someone", "duration": 75,
    "thumbnail": "https://i.iwara.tv/image/thumbnail/x/thumbnail-00.jpg",
    "formats": [
        {"format_id": "preview", "url": "https://files.iwara.tv/p.mp4", "height": None, "vcodec": None},
        {"format_id": "360", "url": "https://files.iwara.tv/360.mp4", "height": 360, "vcodec": None},
        {"format_id": "Source", "url": "https://files.iwara.tv/src.mp4", "height": None, "vcodec": None, "quality": 3},
        {"format_id": "540", "url": "https://files.iwara.tv/540.mp4", "height": 540, "vcodec": None},
    ],
}


class IwaraTests(unittest.TestCase):
    def test_links_are_recognised(self):
        for link in (LINK, "https://ecchi.iwara.tv/video/abc123", "https://iwara.tv/video/abc123"):
            self.assertEqual(pick_adapter(link).name, "iwara")
        self.assertEqual(find_link("look www.iwara.tv/video/abc123 now"), "https://www.iwara.tv/video/abc123")

    def test_video_lists_real_sizes_best_first_and_skips_the_preview(self):
        with mock.patch("mediadl.adapters.iwara.dump_info", return_value=INFO):
            post = pick_adapter(LINK).extract(LINK)
        urls = [c["url"] for c in post["_videos"][0]["cands"]]
        self.assertEqual(urls[0], "https://files.iwara.tv/540.mp4")
        self.assertIn("https://files.iwara.tv/src.mp4", urls)
        self.assertNotIn("https://files.iwara.tv/p.mp4", urls)
        self.assertEqual(post["duration"], "1:15")

    def test_best_quality_prefers_the_source_file(self):
        with mock.patch("mediadl.adapters.iwara.dump_info", return_value=INFO):
            adapter = pick_adapter(LINK)
            cands = adapter.extract(LINK)["_videos"][0]["cands"]
        self.assertEqual(adapter.rank(cands, {"video": {"quality": "best"}})[0]["src"], "Source")
        self.assertEqual(adapter.rank(cands, {"video": {"quality": "360"}})[0]["height"], 360)

    def test_other_pages_are_refused(self):
        with self.assertRaises(ValueError):
            pick_adapter("https://www.iwara.tv/profile/someone").extract("https://www.iwara.tv/profile/someone")


if __name__ == "__main__":
    unittest.main()
