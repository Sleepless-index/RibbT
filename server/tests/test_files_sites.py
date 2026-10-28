import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

YANDERE = "https://yande.re/post/show/1234567"
IMGUR_ALBUM = "https://imgur.com/a/AbCdE12"
IMGUR_PIC = "https://imgur.com/AbCdE12"
IMGUR_DIRECT = "https://i.imgur.com/AbCdE12.jpg"
IMGUR_GIFV = "https://i.imgur.com/AbCdE12.gifv"
CATBOX = "https://files.catbox.moe/abc123.png"


def reply(payload=None, ok=True, text=""):
    response = mock.Mock(ok=ok, text=text, status_code=200 if ok else 403)
    if payload is None:
        response.json.side_effect = ValueError("not json")
    else:
        response.json.return_value = payload
    return response


class FileSiteTests(unittest.TestCase):
    # Imported inside the tests: test_server.py sets its environment before the first import of mediadl.
    def test_links_match_the_right_site(self):
        from mediadl.adapters import find_link, pick_adapter
        self.assertEqual(pick_adapter(YANDERE).name, "yandere")
        self.assertEqual(pick_adapter(IMGUR_ALBUM).name, "imgur")
        self.assertEqual(pick_adapter(IMGUR_DIRECT).name, "imgur")
        self.assertEqual(pick_adapter(CATBOX).name, "catbox")
        self.assertEqual(pick_adapter("https://litter.catbox.moe/xyz789.mp4").name, "catbox")
        self.assertEqual(find_link(f"look {YANDERE} wow"), YANDERE)
        self.assertEqual(find_link(f"look {CATBOX}"), CATBOX)

    def test_yandere_image(self):
        from mediadl.adapters import pick_adapter
        data = [{"id": 1234567, "author": "uploader", "file_ext": "png",
                 "file_url": "https://files.yande.re/image/ab/yande.re%201234567.png",
                 "jpeg_url": "https://files.yande.re/jpeg/ab/yande.re%201234567.jpg",
                 "sample_url": "https://files.yande.re/sample/ab/yande.re%201234567%20sample.jpg"}]
        session = mock.Mock()
        session.get.return_value = reply(data)
        with mock.patch("mediadl.adapters.yandere.get_session", return_value=session):
            post = pick_adapter(YANDERE).extract(YANDERE)
        self.assertEqual(post["_images"][0][0], data[0]["file_url"])
        self.assertEqual(post["_cover"][0], data[0]["sample_url"])
        self.assertEqual((post["title"], post["author"], post["kind"]), ("Yande.re #1234567", "uploader", "images"))
        self.assertEqual(session.get.call_args.kwargs["params"], {"tags": "id:1234567", "limit": 1})

    def test_yandere_page_fallback(self):
        from mediadl.adapters import pick_adapter
        adapter = pick_adapter(YANDERE)
        page = '<a class="original-file-unchanged" id="highres" href="https://files.yande.re/image/cd/full.png">x</a>'
        session = mock.Mock()
        session.get.return_value = reply(None, ok=False)
        with mock.patch("mediadl.adapters.yandere.get_session", return_value=session), \
                mock.patch.object(adapter, "fetch_page", return_value=(YANDERE, page)):
            post = adapter.extract(YANDERE)
        self.assertEqual(post["_images"][0][0], "https://files.yande.re/image/cd/full.png")

    def test_yandere_rejects_a_non_post_link(self):
        from mediadl.adapters import pick_adapter
        with self.assertRaises(ValueError):
            pick_adapter("https://yande.re/post").extract("https://yande.re/post")

    def test_imgur_direct_picture_and_gifv(self):
        from mediadl.adapters import pick_adapter
        post = pick_adapter(IMGUR_DIRECT).extract(IMGUR_DIRECT)
        self.assertEqual(post["_images"], [[IMGUR_DIRECT]])
        self.assertEqual(post["kind"], "images")
        video = pick_adapter(IMGUR_GIFV).extract(IMGUR_GIFV)
        self.assertEqual(video["kind"], "video")
        self.assertEqual(video["_videos"][0]["cands"][0]["url"], "https://i.imgur.com/AbCdE12.mp4")

    def test_imgur_album_mixes_pictures_and_video(self):
        from mediadl.adapters import pick_adapter
        data = {"data": {"title": "Trip", "account_url": "someone", "images": [
            {"link": "https://i.imgur.com/aaaaa11.jpg", "type": "image/jpeg"},
            {"link": "https://i.imgur.com/bbbbb22.gif", "mp4": "https://i.imgur.com/bbbbb22.mp4", "type": "image/gif"},
            {"link": "https://i.imgur.com/ccccc33.png", "type": "image/png"},
        ]}}
        session = mock.Mock()
        session.get.return_value = reply(data)
        with mock.patch("mediadl.adapters.imgur.get_session", return_value=session):
            post = pick_adapter(IMGUR_ALBUM).extract(IMGUR_ALBUM)
        self.assertEqual(post["_images"], [["https://i.imgur.com/aaaaa11.jpg"], ["https://i.imgur.com/ccccc33.png"]])
        self.assertEqual(len(post["_videos"]), 1)
        self.assertEqual((post["title"], post["author"]), ("Trip", "someone"))
        self.assertTrue(session.get.call_args.args[0].endswith("/album/AbCdE12"))
        self.assertTrue(session.get.call_args.kwargs["headers"]["Authorization"].startswith("Client-ID "))

    def test_imgur_single_falls_back_to_the_page(self):
        from mediadl.adapters import pick_adapter
        adapter = pick_adapter(IMGUR_PIC)
        page = '<meta property="og:image" content="https://i.imgur.com/AbCdE12.jpg?fb" />'
        session = mock.Mock()
        session.get.return_value = reply(None, ok=False)
        with mock.patch("mediadl.adapters.imgur.get_session", return_value=session), \
                mock.patch.object(adapter, "fetch_page", return_value=(IMGUR_PIC, page)):
            post = adapter.extract(IMGUR_PIC)
        self.assertEqual(post["_images"], [["https://i.imgur.com/AbCdE12.jpg"]])

    def test_imgur_gone_gives_a_readable_error(self):
        from mediadl.adapters import pick_adapter
        adapter = pick_adapter(IMGUR_ALBUM)
        session = mock.Mock()
        session.get.return_value = reply(None, ok=False)
        with mock.patch("mediadl.adapters.imgur.get_session", return_value=session), \
                mock.patch.object(adapter, "fetch_page", return_value=(IMGUR_ALBUM, "")):
            with self.assertRaises(ValueError) as ctx:
                adapter.extract(IMGUR_ALBUM)
        self.assertIn("Imgur", str(ctx.exception))

    def test_catbox_kinds(self):
        from mediadl.adapters import pick_adapter
        adapter = pick_adapter(CATBOX)
        image = adapter.extract(CATBOX)
        self.assertEqual((image["kind"], image["_images"]), ("images", [[CATBOX]]))
        video = adapter.extract("https://files.catbox.moe/clip01.mp4")
        self.assertEqual(video["kind"], "video")
        self.assertEqual(video["_videos"][0]["cands"][0]["url"], "https://files.catbox.moe/clip01.mp4")
        audio = adapter.extract("https://files.catbox.moe/song01.mp3")
        self.assertEqual((audio["kind"], audio["audio_track"]["url"]), ("audio", "https://files.catbox.moe/song01.mp3"))

    def test_catbox_needs_a_file_link(self):
        from mediadl.adapters import pick_adapter
        for bad in ("https://catbox.moe/", "https://files.catbox.moe/archive.zip"):
            with self.assertRaises(ValueError):
                pick_adapter(bad).extract(bad)

    def test_new_sites_are_allowed_as_media_hosts(self):
        from mediadl.config import MEDIA_HOSTS
        for host in ("yande.re", "imgur.com", "catbox.moe"):
            self.assertIn(host, MEDIA_HOSTS)


if __name__ == "__main__":
    unittest.main()
