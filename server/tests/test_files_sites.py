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
        self.assertEqual(find_link(f"look {YANDERE} wow"), YANDERE)

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

    def test_new_sites_are_allowed_as_media_hosts(self):
        from mediadl.config import MEDIA_HOSTS
        for host in ("yande.re", "imgur.com"):
            self.assertIn(host, MEDIA_HOSTS)


class InstagramPhotoTests(unittest.TestCase):
    LINK = "https://www.instagram.com/p/DegFbL0oQFM/?utm_source=ig_web_button_share_sheet"
    PAGE = (
        '<meta property="og:title" content="𝕂𝔸ℕ𝕆ℕ かのん on Instagram: &quot;#イラスト #drawing&quot;" />'
        '<meta property="og:description" content="25K likes, 41 comments - kanon_25ji on October 14, 2026: &quot;#イラスト&quot;." />'
        '<meta property="og:image" content="https://scontent.cdninstagram.com/v/t51/a_n.jpg?stp=c115.0.345.345a_dst-jpg_e35_s640x640_tt6&amp;_nc_cat=106&amp;oh=abc" />'
    )

    def test_photo_post_falls_back_to_the_preview_page(self):
        from mediadl.adapters import pick_adapter
        adapter = pick_adapter(self.LINK)
        failure = ValueError("Could not parse that Instagram link. ([Instagram] DegFbL0oQFM: There is no video in this post)")
        with mock.patch("mediadl.adapters.instagram.dump_infos", side_effect=failure), \
                mock.patch.object(adapter, "fetch_page", return_value=(self.LINK, self.PAGE)) as fetched:
            post = adapter.extract(self.LINK)
        self.assertEqual(post["_images"], [["https://scontent.cdninstagram.com/v/t51/a_n.jpg?stp=c115.0.345.345a_dst-jpg_e35_s640x640_tt6&_nc_cat=106&oh=abc"]])
        self.assertEqual((post["id"], post["author"], post["kind"]), ("DegFbL0oQFM", "kanon_25ji", "images"))
        self.assertEqual(post["title"], "#イラスト #drawing")
        self.assertIn("facebookexternalhit", fetched.call_args.args[1]["User-Agent"])

    def test_other_failures_are_not_turned_into_a_photo(self):
        from mediadl.adapters import pick_adapter
        adapter = pick_adapter(self.LINK)
        with mock.patch("mediadl.adapters.instagram.dump_infos", side_effect=ValueError("login required")), \
                mock.patch.object(adapter, "fetch_page") as fetched:
            with self.assertRaises(ValueError):
                adapter.extract(self.LINK)
        fetched.assert_not_called()

    def test_unreadable_photo_points_to_cookies(self):
        from mediadl.adapters import pick_adapter
        adapter = pick_adapter(self.LINK)
        with mock.patch("mediadl.adapters.instagram.dump_infos", side_effect=ValueError("There is no video in this post")), \
                mock.patch.object(adapter, "fetch_page", return_value=(self.LINK, "<html></html>")):
            with self.assertRaises(ValueError) as caught:
                adapter.extract(self.LINK)
        self.assertIn("cookies", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
