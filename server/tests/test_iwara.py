import hashlib
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from mediadl.adapters import find_link, pick_adapter  # noqa: E402
from mediadl.adapters import iwara  # noqa: E402

LINK = "https://www.iwara.tv/video/ebvzmck7e0cv90lok/succubus-hutao"
FILE_URL = "https://filesq.iwara.tv/file/f0d79f2d-1a0d?expires=1795418052065&hash=abc"
VIDEO = {
    "id": "ebvzmck7e0cv90lok", "title": "A video", "user": {"name": "Someone"}, "thumbnail": 3,
    "file": {"id": "f0d79f2d"}, "fileUrl": FILE_URL,
}
LISTING = [
    {"name": "Source", "src": {"view": "//a.iwara.tv/view?x=src"}},
    {"name": "540", "src": {"view": "//hime.iwara.tv/view?x=540"}},
    {"name": "360", "src": {"view": "//mikoto.iwara.tv/view?x=360"}},
    {"name": "preview", "src": {"view": "//b.iwara.tv/view?x=preview"}},
]


class FakeResponse:
    def __init__(self, data, status=200):
        self._data, self.status_code = data, status

    def json(self):
        if self._data is None:
            raise ValueError("no body")
        return self._data


class FakeSession:
    def __init__(self, answers):
        self.answers, self.calls = answers, []

    def get(self, url, headers=None, timeout=None):
        self.calls.append((url, headers))
        return FakeResponse(self.answers[url.split("?")[0]])


def _extract(answers):
    session = FakeSession(answers)
    with mock.patch.object(iwara, "get_session", return_value=session):
        return pick_adapter(LINK).extract(LINK), session


class IwaraTests(unittest.TestCase):
    def test_links_are_recognised(self):
        for link in (LINK, "https://ecchi.iwara.tv/video/abc123", "https://iwara.tv/video/abc123"):
            self.assertEqual(pick_adapter(link).name, "iwara")
        self.assertEqual(find_link("look www.iwara.tv/video/abc123 now"), "https://www.iwara.tv/video/abc123")

    def test_sizes_come_from_the_file_list_best_first_without_the_preview(self):
        post, session = _extract({
            "https://apiq.iwara.tv/video/ebvzmck7e0cv90lok": VIDEO,
            "https://filesq.iwara.tv/file/f0d79f2d-1a0d": LISTING,
        })
        urls = [c["url"] for c in post["_videos"][0]["cands"]]
        self.assertEqual(urls, ["https://a.iwara.tv/view?x=src", "https://hime.iwara.tv/view?x=540", "https://mikoto.iwara.tv/view?x=360"])
        self.assertEqual(post["author"], "Someone")
        self.assertIn("thumbnail-03.jpg", str(post["_cover"]))

    def test_requests_carry_the_browser_headers_and_the_signed_version(self):
        _, session = _extract({
            "https://apiq.iwara.tv/video/ebvzmck7e0cv90lok": VIDEO,
            "https://filesq.iwara.tv/file/f0d79f2d-1a0d": LISTING,
        })
        for _, headers in session.calls:
            self.assertEqual(headers["Origin"], "https://www.iwara.tv")
        expected = hashlib.sha1(b"f0d79f2d-1a0d_1795418052065_mSvL05GfEmeEmsEYfGCnVpEjYgTJraJN").hexdigest()
        self.assertEqual(session.calls[1][1]["X-Version"], expected)

    def test_best_quality_prefers_the_source_file(self):
        post, _ = _extract({
            "https://apiq.iwara.tv/video/ebvzmck7e0cv90lok": VIDEO,
            "https://filesq.iwara.tv/file/f0d79f2d-1a0d": LISTING,
        })
        adapter = pick_adapter(LINK)
        cands = post["_videos"][0]["cands"]
        self.assertEqual(adapter.rank(cands, {"video": {"quality": "best"}})[0]["src"], "Source")
        self.assertEqual(adapter.rank(cands, {"video": {"quality": "360"}})[0]["height"], 360)

    def test_private_and_blocked_videos_say_so(self):
        with self.assertRaises(ValueError) as caught:
            _extract({"https://apiq.iwara.tv/video/ebvzmck7e0cv90lok": {"message": "errors.privateVideo"}})
        self.assertIn("private", str(caught.exception))
        session = FakeSession({"https://apiq.iwara.tv/video/ebvzmck7e0cv90lok": None})
        with mock.patch.object(iwara, "get_session", return_value=session), self.assertRaises(ValueError) as caught:
            pick_adapter(LINK).extract(LINK)
        self.assertIn("blocking", str(caught.exception))

    def test_other_pages_are_refused(self):
        with self.assertRaises(ValueError):
            pick_adapter("https://www.iwara.tv/profile/someone").extract("https://www.iwara.tv/profile/someone")


if __name__ == "__main__":
    unittest.main()
