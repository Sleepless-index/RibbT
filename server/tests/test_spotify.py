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
                mock.patch("mediadl.adapters.spotify.dump_infos", return_value=[info]) as dump:
            post = adapter.extract(TRACK)
        self.assertIn("ytsearch4:Rick Astley Never Gonna Give You Up", dump.call_args[0][0])
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
                mock.patch("mediadl.adapters.spotify.dump_infos", return_value=[{"id": "bad", "formats": []}, info]):
            post = adapter.extract(TRACK + "?si=abc&utm_source=copy-link")
        self.assertEqual(post["title"], "Never Gonna Give You Up")
        self.assertEqual(post["_cover"][0], "https://i.scdn.co/image/abc")

    def test_search_reads_every_result_from_one_document(self):
        import json
        import subprocess
        from mediadl.adapters import ytdlp

        done = subprocess.CompletedProcess([], 0, json.dumps({"entries": [{"id": "a"}, None, {"id": "b"}]}) + "\n", "")
        with mock.patch.object(ytdlp.ytdlp_runner, "run_playlist", return_value=done), \
                mock.patch.object(ytdlp.cookie_store, "active_path", return_value=None):
            self.assertEqual(ytdlp.dump_infos("ytsearch4:x", "Spotify"), [{"id": "a"}, {"id": "b"}])

    def test_falls_back_to_soundcloud(self):
        adapter = pick_adapter(TRACK)
        sc = {"id": "1", "title": "x", "uploader": "u", "duration": 213, "thumbnail": "https://i1.sndcdn.com/a.jpg", "formats": [
            {"url": "https://cf-media.sndcdn.com/a.mp3", "vcodec": "none", "acodec": "mp3", "abr": 128, "protocol": "https"}]}
        wrong = dict(sc, id="2", duration=60)

        def search(query, label, *a):
            return [{"id": "y", "formats": []}] if query.startswith("ytsearch") else [wrong, sc]

        page = PAGE.replace("&middot;", "·") + '<meta name="music:duration" content="212"/>'
        with mock.patch.object(adapter, "fetch_page", return_value=(TRACK, page)), \
                mock.patch("mediadl.adapters.spotify.dump_infos", side_effect=search):
            post = adapter.extract(TRACK)
        self.assertEqual(post["kind"], "audio")
        self.assertEqual(post["audio_track"]["url"], "https://cf-media.sndcdn.com/a.mp3")
        self.assertEqual((post["title"], post["author"]), ("Never Gonna Give You Up", "Rick Astley"))


class AudioCoverTests(unittest.TestCase):
    def test_cover_only_for_formats_that_can_carry_it(self):
        from mediadl.services.transforms import audio_transform

        self.assertIn("1:v:0", audio_transform("mp3", 192, {}, True).args)
        self.assertIn("1:v:0", audio_transform("m4a", 192, {}, True).args)
        self.assertNotIn("1:v:0", audio_transform("opus", 192, {}, True).args)
        self.assertIn("-vn", audio_transform("mp3", 192, {}, False).args)

    def test_audio_links_carry_cover_and_preview(self):
        from mediadl.services.appview import build_app_post

        post = {"id": "1", "title": "T", "author": "A", "kind": "audio", "cover": "https://i.scdn.co/image/abc",
                "audio_track": {"url": "https://cf-hls-media.sndcdn.com/p.m3u8", "hls": True}}
        prefs = {"metadata": {"filename_style": "classic"}, "audio": {"tiktok_original": False}, "x": {"loop_as_gif": False}}
        item = build_app_post(post, "soundcloud", prefs, "https://soundcloud.com/a/b")["items"][0]
        self.assertIn("cover=https%3A%2F%2Fi.scdn.co", item["downloads"][0]["url"])
        self.assertIn("hls=1", item["downloads"][0]["url"])
        self.assertTrue(item["play_audio"].startswith("/api/audio-preview"))


class SoundCloudInstagramTests(unittest.TestCase):
    def test_soundcloud_audio_post_and_app_item(self):
        from mediadl.services.appview import build_app_post

        adapter = pick_adapter("https://soundcloud.com/artist/track")
        self.assertEqual(adapter.name, "soundcloud")
        info = {"id": 5, "title": "Track", "uploader": "Artist", "duration": 100, "thumbnail": "https://i1.sndcdn.com/a.jpg",
                "formats": [{"url": "https://cf-media.sndcdn.com/a.mp3", "vcodec": "none", "acodec": "mp3", "abr": 128, "protocol": "https"},
                            {"url": "https://x/h.m3u8", "vcodec": "none", "acodec": "mp3", "abr": 256, "protocol": "m3u8_native"}]}
        with mock.patch("mediadl.adapters.soundcloud.dump_info", return_value=info):
            post = adapter.extract("https://soundcloud.com/artist/track")
        post["cover"] = post.pop("_cover")[0]
        prefs = {"metadata": {"filename_style": "classic"}, "audio": {"tiktok_original": False}, "x": {"loop_as_gif": False}}
        app = build_app_post(post, "soundcloud", prefs, "https://soundcloud.com/artist/track")
        self.assertEqual(app["kind"], "audio")
        download = app["items"][0]["downloads"][0]
        self.assertEqual(download["label"], "Audio")
        self.assertIn("title=Track", download["url"])
        self.assertIn("artist=Artist", download["url"])

    def test_soundcloud_hls_only_track(self):
        from mediadl.adapters.soundcloud import audio_post

        info = {"id": 9, "title": "T", "uploader": "U", "formats": [
            {"url": "https://cf-hls-media.sndcdn.com/p.m3u8", "format_id": "hls_aac_160k", "vcodec": "none",
             "acodec": "aac", "abr": 160, "protocol": "m3u8_native"},
            {"url": "https://cf-hls-media.sndcdn.com/x.m3u8", "format_id": "hls_mp3_128_preview", "vcodec": "none",
             "acodec": "mp3", "abr": 999, "protocol": "m3u8_native"}]}
        post = audio_post(info)
        self.assertTrue(post["audio_track"]["hls"])
        self.assertTrue(post["audio_track"]["url"].endswith("p.m3u8"))

    def test_instagram_links_and_carousel(self):
        adapter = pick_adapter("https://www.instagram.com/reel/Cabc123/?igsh=x")
        self.assertEqual(adapter.name, "instagram")
        self.assertIsNone(pick_adapter("https://www.instagram.com/someone/"))
        entries = [
            {"id": "1", "display_id": "Cabc", "title": "Hello", "uploader": "someone", "ext": "jpg", "url": "https://scontent.cdninstagram.com/a.jpg"},
            {"id": "2", "ext": "jpg", "url": "https://scontent.cdninstagram.com/b.jpg"},
        ]
        with mock.patch("mediadl.adapters.instagram.dump_infos", return_value=entries):
            post = adapter.extract("https://www.instagram.com/p/Cabc/")
        self.assertEqual(len(post["_images"]), 2)
        self.assertEqual((post["author"], post["title"]), ("someone", "Hello"))

    def test_instagram_vertical_video_labelled_by_short_side(self):
        from mediadl.adapters.instagram import _by_short_side

        fixed = _by_short_side([{"width": 720, "height": 1280, "url": "u"}, {"url": "v"}])
        self.assertEqual((fixed[0]["height"], fixed[1].get("height")), (720, None))


if __name__ == "__main__":
    unittest.main()
