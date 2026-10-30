import io
import os
import sys
import unittest
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

ART = "https://www.pixiv.net/en/artworks/151404264"
ZIP = "https://i.pximg.net/img-zip-ugoira/img/2020/09/20/03/53/14/84483817_ugoira600x600.zip"
URLS = {
    "small": "https://i.pximg.net/c/540x540_70/img-master/img/2026/10/29/x_p0_master1200.jpg",
    "regular": "https://i.pximg.net/img-master/img/2026/10/29/x_p0_master1200.jpg",
    "original": "https://i.pximg.net/img-original/img/2026/10/29/x_p0.jpg",
}


def fake_api(table):
    return lambda path: table.get(path)


class PixivTests(unittest.TestCase):
    def test_links(self):
        from mediadl.adapters import find_link, pick_adapter
        from mediadl.adapters.pixiv import artwork_id
        for link in (ART, "https://www.pixiv.net/artworks/143093572", "https://pixiv.net/i/99",
                     "https://www.pixiv.net/member_illust.php?mode=medium&illust_id=77"):
            self.assertEqual(pick_adapter(link).name, "pixiv")
        self.assertEqual(artwork_id(ART), "151404264")
        self.assertEqual(artwork_id("https://pixiv.net/i/99"), "99")
        self.assertEqual(artwork_id("https://www.pixiv.net/member_illust.php?mode=medium&illust_id=77"), "77")
        self.assertIsNone(artwork_id("https://www.pixiv.net/en/users/67093476"))
        self.assertEqual(find_link(f"see {ART}, nice"), ART)

    def test_single_and_multi_page(self):
        from mediadl.adapters import pick_adapter
        info = {"illustType": 0, "title": "知更鸟", "userName": "日宝", "userId": "5", "urls": URLS}
        pages = [{"urls": {"original": f"https://i.pximg.net/img-original/a_p{i}.jpg",
                           "regular": f"https://i.pximg.net/img-master/a_p{i}_master1200.jpg"}} for i in range(3)]
        table = {"/illust/151404264": info, "/illust/151404264/pages": pages,
                 "/user/5?full=0": {"imageBig": "https://i.pximg.net/user-profile/img/a_170.jpg"}}
        with mock.patch("mediadl.adapters.pixiv.api", fake_api(table)):
            post = pick_adapter(ART).extract(ART)
        self.assertEqual(len(post["_images"]), 3)
        self.assertEqual(post["_images"][0][0], "https://i.pximg.net/img-original/a_p0.jpg")  # original first
        self.assertEqual(post["_cover"][0], URLS["regular"])
        self.assertEqual((post["title"], post["author"], post["kind"]), ("知更鸟", "日宝", "images"))
        self.assertTrue(post["avatar"].endswith("a_170.jpg"))

    def test_pages_failure_falls_back_to_the_main_urls(self):
        from mediadl.adapters import pick_adapter
        table = {"/illust/151404264": {"illustType": 0, "title": "t", "urls": URLS}}
        with mock.patch("mediadl.adapters.pixiv.api", fake_api(table)):
            post = pick_adapter(ART).extract(ART)
        self.assertEqual(post["_images"], [[URLS["original"], URLS["regular"]]])

    def test_ugoira(self):
        from mediadl.adapters import pick_adapter
        link = "https://www.pixiv.net/en/artworks/84483817"
        frames = [{"file": "000000.jpg", "delay": 100}, {"file": "000001.jpg", "delay": 2400}]
        table = {"/illust/84483817": {"illustType": 2, "title": "a", "height": 1333, "urls": URLS},
                 "/illust/84483817/ugoira_meta": {"originalSrc": ZIP, "src": ZIP, "frames": frames}}
        with mock.patch("mediadl.adapters.pixiv.api", fake_api(table)):
            post = pick_adapter(link).extract(link)
        self.assertEqual(post["kind"], "video")
        self.assertEqual(post["_videos"][0]["cands"][0]["url"], ZIP)
        self.assertTrue(post["_videos"][0]["ugoira"])
        self.assertEqual(post["duration"], "0:03")

    def test_unreadable_artwork_says_why(self):
        from mediadl.adapters import pick_adapter
        with mock.patch("mediadl.adapters.pixiv.api", fake_api({})):
            with self.assertRaises(ValueError) as caught:
                pick_adapter(ART).extract(ART)
        self.assertIn("signed-in", str(caught.exception))
        with self.assertRaises(ValueError):
            pick_adapter(ART).extract("https://www.pixiv.net/en/users/1")

    def test_app_item_for_ugoira_has_no_audio_and_no_inline_player(self):
        from mediadl.services.appview import build_app_post
        from mediadl.preferences import load_defaults
        post = {"id": "84483817", "title": "a", "author": "b", "kind": "video", "cover": URLS["regular"], "images": [],
                "videos": [{"url": ZIP, "src": None, "label": "Video", "ugoira": True, "preview": URLS["regular"]}]}
        app = build_app_post(post, "pixiv", load_defaults(), "https://www.pixiv.net/artworks/84483817")
        video = app["items"][0]
        self.assertIsNone(video["play"])
        self.assertEqual([d["label"] for d in video["downloads"]], ["Download"])
        self.assertIn("/api/video", video["downloads"][0]["url"])

    def test_image_host_gets_the_pixiv_referer(self):
        from mediadl.services.net import header_sets
        sets = header_sets("https://i.pximg.net/img-original/a_p0.jpg", {})
        self.assertEqual(sets[0]["Referer"], "https://www.pixiv.net/")


@unittest.skipUnless(__import__("shutil").which("ffmpeg"), "ffmpeg not installed")
class UgoiraConvertTests(unittest.TestCase):
    def test_zip_becomes_a_video_with_the_right_length(self):
        import subprocess
        from PIL import Image
        from mediadl.services import ugoira

        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            for i, colour in enumerate(("red", "green", "blue")):
                image = io.BytesIO()
                Image.new("RGB", (63, 45), colour).save(image, "JPEG")  # odd size: must be padded to even
                archive.writestr(f"{i:06d}.jpg", image.getvalue())
        zip_path = Path(os.environ.get("TMPDIR", "/tmp")) / "ugoira_test.zip"
        zip_path.write_bytes(buffer.getvalue())
        frames = [{"file": "000000.jpg", "delay": 500}, {"file": "000001.jpg", "delay": 1000}, {"file": "000002.jpg", "delay": 500}]

        captured = {}

        def fake_encode(paths, name, transform, network_urls=None):
            out = str(zip_path) + ".mp4"
            subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0",
                            "-i", paths[0], *transform.args, out], check=True)
            captured["out"], captured["args"] = out, transform.args
            return "ok"

        with mock.patch.object(ugoira, "_frames", return_value=frames), \
                mock.patch.object(ugoira, "fetch_sources", return_value=[str(zip_path)]), \
                mock.patch.object(ugoira, "encode_local", fake_encode):
            ugoira.convert(ZIP, "x", "mp4")
        probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                                captured["out"]], capture_output=True, text=True).stdout.strip()
        self.assertAlmostEqual(float(probe), 2.0, delta=0.2)  # 0.5 + 1.0 + 0.5 seconds


if __name__ == "__main__":
    unittest.main()
