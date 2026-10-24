import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

DANBOORU = "https://danbooru.donmai.us/posts/7654321?q=order%3Arank"
GELBOORU = "https://gelbooru.com/index.php?page=post&s=view&id=9988776&tags=all"


def reply(payload=None, ok=True, text=""):
    response = mock.Mock(ok=ok, text=text)
    if payload is None:
        response.json.side_effect = ValueError("not json")
    else:
        response.json.return_value = payload
    return response


class BooruTests(unittest.TestCase):
    # Imported inside the tests: test_server.py sets its environment before the first import of mediadl.
    def test_links_match_the_right_site(self):
        from mediadl.adapters import find_link, pick_adapter
        self.assertEqual(pick_adapter(DANBOORU).name, "danbooru")
        self.assertEqual(pick_adapter("https://safebooru.donmai.us/posts/12").name, "danbooru")
        self.assertEqual(pick_adapter(GELBOORU).name, "gelbooru")
        self.assertEqual(find_link(f"nice {DANBOORU}"), DANBOORU)

    def test_danbooru_image(self):
        from mediadl.adapters import pick_adapter
        adapter = pick_adapter(DANBOORU)
        data = {"id": 7654321, "file_ext": "png", "file_url": "https://cdn.donmai.us/original/ab/cd/abcd.png",
                "large_file_url": "https://cdn.donmai.us/sample/ab/cd/sample-abcd.jpg",
                "preview_file_url": "https://cdn.donmai.us/180x180/ab/cd/abcd.jpg",
                "tag_string_artist": "some_artist", "tag_string_character": "hatsune_miku_(vocaloid) kagamine_rin"}
        session = mock.Mock()
        session.get.return_value = reply(data)
        with mock.patch("mediadl.adapters.danbooru.get_session", return_value=session):
            post = adapter.extract(DANBOORU)
        self.assertEqual((post["title"], post["author"]), ("Hatsune Miku, Kagamine Rin", "Some Artist"))
        self.assertEqual(post["_images"][0][0], data["file_url"])
        self.assertEqual(post["kind"], "images")

    def test_danbooru_guest_blocked(self):
        from mediadl.adapters import pick_adapter
        adapter = pick_adapter(DANBOORU)
        session = mock.Mock()
        session.get.return_value = reply({"id": 7654321, "file_ext": "jpg"})
        with mock.patch("mediadl.adapters.danbooru.get_session", return_value=session):
            with self.assertRaises(ValueError):
                adapter.extract(DANBOORU)

    def test_danbooru_ugoira_uses_the_video(self):
        from mediadl.adapters import pick_adapter
        adapter = pick_adapter(DANBOORU)
        data = {"id": 7654321, "file_ext": "zip", "file_url": "https://cdn.donmai.us/original/x.zip",
                "large_file_url": "https://cdn.donmai.us/sample/x.webm"}
        session = mock.Mock()
        session.get.return_value = reply(data)
        with mock.patch("mediadl.adapters.danbooru.get_session", return_value=session):
            post = adapter.extract(DANBOORU)
        self.assertEqual(post["kind"], "video")
        self.assertTrue(post["_videos"][0]["cands"][0]["url"].endswith(".webm"))

    def test_gelbooru_api_shape(self):
        from mediadl.adapters import pick_adapter
        adapter = pick_adapter(GELBOORU)
        data = {"@attributes": {"count": 1}, "post": [{"id": 9988776, "owner": "someone",
                "file_url": "https://img3.gelbooru.com/images/aa/bb/aabb.jpg",
                "sample_url": "https://img3.gelbooru.com/samples/aa/bb/sample_aabb.jpg"}]}
        session = mock.Mock()
        session.get.return_value = reply(data)
        with mock.patch("mediadl.adapters.dapi.get_session", return_value=session):
            post = adapter.extract(GELBOORU)
        self.assertEqual(post["_images"][0][0], "https://img3.gelbooru.com/images/aa/bb/aabb.jpg")
        self.assertEqual(post["author"], "someone")

    def test_gelbooru_page_fallback_when_api_wants_a_key(self):
        from mediadl.adapters import pick_adapter
        adapter = pick_adapter(GELBOORU)
        page = '<a href="https://img3.gelbooru.com/images/aa/bb/aabb.png" target="_blank">Original image</a>'
        session = mock.Mock()
        session.get.return_value = reply(None, ok=False)
        with mock.patch("mediadl.adapters.dapi.get_session", return_value=session), \
                mock.patch.object(adapter, "fetch_page", return_value=(GELBOORU, page)):
            post = adapter.extract(GELBOORU)
        self.assertEqual(post["_images"][0][0], "https://img3.gelbooru.com/images/aa/bb/aabb.png")


if __name__ == "__main__":
    unittest.main()
