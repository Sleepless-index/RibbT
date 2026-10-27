import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

VIEW = "https://safebooru.org/index.php?page=post&s=view&id=4567890"
RECORD = {"id": 4567890, "directory": "3841", "image": "abc123.png", "hash": "abc123",
          "sample": 1, "owner": "someone", "tags": "1girl solo"}


def reply(payload=None, text="", ok=True):
    response = mock.Mock(ok=ok, text=text, status_code=200 if ok else 403)
    if payload is None:
        response.json.side_effect = ValueError("not json")
    else:
        response.json.return_value = payload
    return response


class SafebooruTests(unittest.TestCase):
    def setUp(self):
        # Imported here, not at module level: test_server.py sets its environment before the first import of mediadl.
        from mediadl.adapters import pick_adapter
        self.adapter = pick_adapter(VIEW)

    def test_matches_and_finds_links(self):
        from mediadl.adapters import find_link
        from mediadl.adapters.dapi import post_id
        self.assertEqual(self.adapter.name, "safebooru")
        self.assertEqual(find_link(f"look {VIEW}"), VIEW)
        self.assertEqual(post_id(VIEW), "4567890")
        self.assertEqual(post_id("https://safebooru.org/index.php?page=post&s=list&tags=x"), None)

    def test_extract_from_api(self):
        session = mock.Mock()
        session.get.return_value = reply([RECORD])
        with mock.patch("mediadl.adapters.dapi.get_session", return_value=session):
            post = self.adapter.extract(VIEW)
        self.assertEqual(post["kind"], "images")
        self.assertEqual(post["author"], "someone")
        self.assertEqual(post["_images"][0][0], "https://safebooru.org/images/3841/abc123.png")
        self.assertIn("https://safebooru.org/samples/3841/sample_abc123.jpg", post["_images"][0])

    def test_extract_from_xml(self):
        xml = '<posts count="1"><post id="4567890" directory="3841" image="abc123.jpg" hash="abc123" sample="false" owner="o"/></posts>'
        session = mock.Mock()
        session.get.return_value = reply(None, text=xml)
        with mock.patch("mediadl.adapters.dapi.get_session", return_value=session):
            post = self.adapter.extract(VIEW)
        self.assertEqual(post["_images"][0], ["https://safebooru.org/images/3841/abc123.jpg"])

    def test_video_post(self):
        session = mock.Mock()
        session.get.return_value = reply([{**RECORD, "image": "clip.webm", "sample": 0}])
        with mock.patch("mediadl.adapters.dapi.get_session", return_value=session):
            post = self.adapter.extract(VIEW)
        self.assertEqual(post["kind"], "video")
        self.assertTrue(post["_videos"][0]["cands"][0]["url"].endswith("clip.webm"))

    def test_page_fallback(self):
        page = '<img alt="x" id="image" src="//safebooru.org/images/3841/abc123.jpg?4567890"/>'
        session = mock.Mock()
        session.get.return_value = reply(None, ok=False)
        with mock.patch("mediadl.adapters.dapi.get_session", return_value=session), \
                mock.patch.object(self.adapter, "fetch_page", return_value=(VIEW, page)):
            post = self.adapter.extract(VIEW)
        self.assertEqual(post["_images"][0][0], "https://safebooru.org/images/3841/abc123.jpg?4567890")

    def test_list_link_rejected(self):
        with self.assertRaises(ValueError):
            self.adapter.extract("https://safebooru.org/index.php?page=post&s=list")


if __name__ == "__main__":
    unittest.main()
