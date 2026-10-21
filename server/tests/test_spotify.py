import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from mediadl.adapters import find_link, pick_adapter  # noqa: E402
from mediadl.adapters.spotify import parse_track_page  # noqa: E402

TRACK = "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"
PAGE = (
    '<meta property="og:title" content="Never Gonna Give You Up"/>'
    '<meta property="og:description" content="Rick Astley &middot; Whenever You Need Somebody &middot; Song &middot; 1987"/>'
    '<meta property="og:image" content="https://i.scdn.co/image/abc"/>'
)


class SpotifyTests(unittest.TestCase):
    def test_only_track_links_match(self):
        self.assertEqual(pick_adapter(TRACK).name, "spotify")
        self.assertEqual(pick_adapter(TRACK.replace("/track/", "/intl-de/track/")).name, "spotify")
        self.assertIsNone(pick_adapter("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        self.assertEqual(find_link(f"listen {TRACK}?si=x"), TRACK + "?si=x")

    def test_parse_page(self):
        page = PAGE.replace("&middot;", "·")
        self.assertEqual(
            parse_track_page(page),
            ("Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody", "https://i.scdn.co/image/abc"),
        )

    def test_extract_uses_spotify_details(self):
        adapter = pick_adapter(TRACK)
        info = {"id": "dQw4w9WgXcQ", "title": "Rick Astley - Never Gonna Give You Up (Official Video)",
                "uploader": "Rick Astley", "duration": 213, "formats": [
                    {"url": "https://rr.googlevideo.com/v", "vcodec": "avc1", "acodec": "none", "height": 720, "protocol": "https"},
                    {"url": "https://rr.googlevideo.com/a", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 128, "protocol": "https"}]}
        with mock.patch.object(adapter, "fetch_page", return_value=(TRACK, PAGE.replace("&middot;", "·"))), \
                mock.patch("mediadl.adapters.spotify.dump_info", return_value=info) as dump:
            post = adapter.extract(TRACK)
        self.assertIn("ytsearch1:Rick Astley Never Gonna Give You Up", dump.call_args[0][0])
        self.assertEqual((post["title"], post["author"], post["album"]), ("Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody"))
        self.assertEqual(post["_cover"][0], "https://i.scdn.co/image/abc")

    def test_oembed_fallback_when_page_fails(self):
        adapter = pick_adapter(TRACK)
        info = {"id": "dQw4w9WgXcQ", "title": "x", "uploader": "u", "formats": [
            {"url": "https://rr.googlevideo.com/v", "vcodec": "avc1", "acodec": "mp4a", "height": 720, "protocol": "https"}]}
        response = mock.Mock()
        response.json.return_value = {"title": "Never Gonna Give You Up", "thumbnail_url": "https://i.scdn.co/image/abc"}
        session = mock.Mock()
        session.get.return_value = response
        with mock.patch.object(adapter, "fetch_page", side_effect=ValueError("Link redirected somewhere unexpected.")), \
                mock.patch("mediadl.adapters.spotify.get_session", return_value=session), \
                mock.patch("mediadl.adapters.spotify.dump_info", return_value=info):
            post = adapter.extract(TRACK + "?si=abc&utm_source=copy-link")
        self.assertEqual(post["title"], "Never Gonna Give You Up")
        self.assertEqual(post["_cover"][0], "https://i.scdn.co/image/abc")


if __name__ == "__main__":
    unittest.main()
