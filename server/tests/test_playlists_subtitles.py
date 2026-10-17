import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

_ROOT = tempfile.mkdtemp(prefix="ribbt-ps-")
os.environ.setdefault("RIBBT_TMP_DIR", os.path.join(_ROOT, "tmp"))
os.environ.setdefault("RIBBT_COOKIES_PATH", os.path.join(_ROOT, "cookies.txt"))

create_app = pick_adapter = ytdlp_helpers = subtitles = ytdlp_runner = None
build_app_playlist = build_app_post = build_filename = None


def setUpModule():
    global create_app, pick_adapter, ytdlp_helpers, subtitles, ytdlp_runner
    global build_app_playlist, build_app_post, build_filename
    from mediadl import create_app as app_factory
    from mediadl.adapters import pick_adapter as picker
    from mediadl.adapters import ytdlp as helpers
    from mediadl.services import subtitles as subtitle_service
    from mediadl.services import ytdlp_runner as runner
    from mediadl.services.appview import build_app_playlist as playlist_view
    from mediadl.services.appview import build_app_post as post_view
    from mediadl.services.filenames import build_filename as namer

    create_app, pick_adapter, ytdlp_helpers = app_factory, picker, helpers
    subtitles, ytdlp_runner = subtitle_service, runner
    build_app_playlist, build_app_post, build_filename = playlist_view, post_view, namer

PREFS = {
    "metadata": {"filename_style": "classic"},
    "audio": {"tiktok_original": False},
    "x": {"loop_as_gif": False},
}

FLAT = {
    "id": "PLabc",
    "title": "Road trip",
    "uploader": "Someone",
    "playlist_count": 3,
    "entries": [
        {"id": "aaaaaaaaaaa", "title": "One", "duration": 65, "url": "https://www.youtube.com/watch?v=aaaaaaaaaaa", "uploader": "A"},
        {"id": "bbbbbbbbbbb", "title": "[Private video]", "url": "https://www.youtube.com/watch?v=bbbbbbbbbbb"},
        {"id": "ccccccccccc", "title": "Three", "duration": 3725},
    ],
}

VTT = """WEBVTT
Kind: captions

00:00:01.000 --> 00:00:02.500 align:start position:0%
Hello <c>there</c> &amp; welcome

NOTE skip me

00:03.000 --> 00:04.250
Second line
"""


class FakeRun:
    def __init__(self, stdout="", stderr="", code=0):
        self.stdout, self.stderr, self.returncode = stdout, stderr, code


class PlaylistDetection(unittest.TestCase):
    def setUp(self):
        self.youtube = pick_adapter("https://www.youtube.com/watch?v=x")

    def test_playlist_pages_are_recognised(self):
        self.assertTrue(self.youtube.is_playlist("https://www.youtube.com/playlist?list=PL123"))
        self.assertTrue(self.youtube.is_playlist("https://www.youtube.com/watch?list=RDabc"))
        self.assertTrue(self.youtube.is_playlist("https://m.youtube.com/playlist?list=PL123&si=zz"))

    def test_a_video_inside_a_playlist_is_still_a_video(self):
        link = "https://www.youtube.com/watch?v=aaaaaaaaaaa&list=PL123"
        self.assertFalse(self.youtube.is_playlist(link))
        self.assertTrue(self.youtube.has_playlist(link))
        self.assertFalse(self.youtube.has_playlist("https://www.youtube.com/watch?v=aaaaaaaaaaa"))
        self.assertFalse(self.youtube.is_playlist("https://youtu.be/aaaaaaaaaaa?list=PL123"))

    def test_other_sites_have_no_playlists(self):
        reddit = pick_adapter("https://www.reddit.com/r/x/comments/1/a/")
        self.assertFalse(reddit.is_playlist("https://www.reddit.com/r/x/comments/1/a/"))
        with self.assertRaises(ValueError):
            reddit.extract_playlist("https://www.reddit.com/r/x/comments/1/a/")

    def test_bilibili_lists(self):
        bilibili = pick_adapter("https://www.bilibili.com/video/BV1")
        self.assertTrue(bilibili.is_playlist("https://space.bilibili.com/1/favlist?fid=2"))
        self.assertFalse(bilibili.is_playlist("https://www.bilibili.com/video/BV1?p=2"))


class PlaylistExtraction(unittest.TestCase):
    def test_entries_are_cleaned_and_unavailable_ones_dropped(self):
        payload = ytdlp_helpers.playlist_payload(
            FLAT, "https://www.youtube.com/watch?v=", "https://i.ytimg.com/vi/{id}/mqdefault.jpg"
        )
        self.assertEqual([e["title"] for e in payload["entries"]], ["One", "Three"])
        self.assertEqual(payload["entries"][0]["duration"], "1:05")
        self.assertEqual(payload["entries"][1]["duration"], "1:02:05")
        self.assertEqual(payload["entries"][1]["url"], "https://www.youtube.com/watch?v=ccccccccccc")
        self.assertEqual(payload["entries"][0]["thumb"], "https://i.ytimg.com/vi/aaaaaaaaaaa/mqdefault.jpg")
        self.assertEqual(payload["total"], 3)

    def test_youtube_playlist_goes_through_yt_dlp_flat(self):
        youtube = pick_adapter("https://www.youtube.com/watch?v=x")
        seen = {}

        def fake(args, timeout):
            seen["args"] = args
            return FakeRun(json.dumps(FLAT))

        with mock.patch.object(ytdlp_runner, "run_playlist", fake):
            payload = youtube.extract_playlist("https://www.youtube.com/watch?v=aaaaaaaaaaa&list=PL123")
        self.assertIn("--flat-playlist", seen["args"])
        self.assertEqual(seen["args"][-1], "https://www.youtube.com/playlist?list=PL123")
        self.assertEqual(len(payload["entries"]), 2)

    def test_a_mix_keeps_its_video(self):
        youtube = pick_adapter("https://www.youtube.com/watch?v=x")
        seen = {}

        def fake(args, timeout):
            seen["args"] = args
            return FakeRun(json.dumps(FLAT))

        with mock.patch.object(ytdlp_runner, "run_playlist", fake):
            youtube.extract_playlist("https://www.youtube.com/watch?v=aaaaaaaaaaa&list=RDaaaaaaaaaaa")
        self.assertEqual(seen["args"][-1], "https://www.youtube.com/watch?v=aaaaaaaaaaa&list=RDaaaaaaaaaaa")

    def test_a_mix_link_without_a_video_uses_the_id_inside_the_list(self):
        youtube = pick_adapter("https://www.youtube.com/watch?v=x")
        seen = {}

        def fake(args, timeout):
            seen["args"] = args
            return FakeRun(json.dumps(FLAT))

        link = "https://youtube.com/playlist?list=RDT67ZMQcLy-c&playnext=1&si=-4j20NCcaHuq0mwy"
        self.assertTrue(youtube.is_playlist(link))
        with mock.patch.object(ytdlp_runner, "run_playlist", fake):
            youtube.extract_playlist(link)
        self.assertEqual(seen["args"][-1], "https://www.youtube.com/watch?v=T67ZMQcLy-c&list=RDT67ZMQcLy-c")

    def test_failure_is_reported_as_a_readable_error(self):
        youtube = pick_adapter("https://www.youtube.com/watch?v=x")
        failing = FakeRun(stderr="ERROR: The playlist does not exist.", code=1)
        with mock.patch.object(ytdlp_runner, "run_playlist", lambda a, t: failing):
            with self.assertRaises(ValueError) as caught:
                youtube.extract_playlist("https://www.youtube.com/playlist?list=PLnope")
        self.assertIn("playlist does not exist", str(caught.exception))

    def test_app_payload_points_thumbnails_at_the_media_proxy(self):
        payload = build_app_playlist(
            {"title": "T", "author": "A", "total": 9, "entries": [
                {"id": "x1", "title": "One", "author": "", "duration": "1:00",
                 "url": "https://www.youtube.com/watch?v=x1", "thumb": "https://i.ytimg.com/vi/x1/mqdefault.jpg"},
                {"id": "x2", "title": "Two", "author": "", "duration": "", "url": "https://www.youtube.com/watch?v=x2", "thumb": ""},
            ]},
            "youtube", "https://www.youtube.com/playlist?list=PL1",
        )
        self.assertEqual(payload["type"], "playlist")
        self.assertEqual(payload["total"], 9)
        self.assertTrue(payload["entries"][0]["thumb"].startswith("/api/media?u="))
        self.assertIsNone(payload["entries"][1]["thumb"])


class AppRoutes(unittest.TestCase):
    def setUp(self):
        self.client = create_app().test_client()

    def test_playlist_link_returns_a_playlist(self):
        youtube = pick_adapter("https://www.youtube.com/watch?v=x")
        with mock.patch.object(ytdlp_runner, "run_playlist", lambda a, t: FakeRun(json.dumps(FLAT))):
            response = self.client.post("/api/app/parse", json={"url": "https://www.youtube.com/playlist?list=PL123"})
        self.assertEqual(response.status_code, 200)
        data = response.get_json()
        self.assertEqual(data["type"], "playlist")
        self.assertEqual(len(data["entries"]), 2)
        self.assertIsNotNone(youtube)

    def test_playlist_endpoint_rejects_plain_videos(self):
        response = self.client.post("/api/app/playlist", json={"url": "https://www.youtube.com/watch?v=aaaaaaaaaaa"})
        self.assertEqual(response.status_code, 400)

    def test_playlist_endpoint_reports_site_errors(self):
        failing = FakeRun(stderr="ERROR: nope", code=1)
        with mock.patch.object(ytdlp_runner, "run_playlist", lambda a, t: failing):
            response = self.client.post(
                "/api/app/playlist", json={"url": "https://www.youtube.com/watch?v=aaaaaaaaaaa&list=PL1"}
            )
        self.assertEqual(response.status_code, 400)
        self.assertIn("error", response.get_json())


class SubtitleTracks(unittest.TestCase):
    INFO = {
        "language": "en",
        "subtitles": {
            "en": [{"ext": "vtt", "name": "English"}],
            "fr": [{"ext": "vtt", "name": "French"}],
            "live_chat": [{"ext": "json"}],
        },
        "automatic_captions": {
            "en-orig": [{"ext": "vtt", "name": "English (Original)"}],
            "de": [{"ext": "vtt", "name": "German"}],
        },
    }

    def test_manual_tracks_come_first_and_auto_only_for_the_spoken_language(self):
        tracks = ytdlp_helpers.subtitle_tracks(self.INFO)
        self.assertEqual([t["lang"] for t in tracks], ["en", "fr"])

    def test_auto_track_is_offered_when_there_is_no_manual_one(self):
        info = {"language": "en", "automatic_captions": self.INFO["automatic_captions"]}
        tracks = ytdlp_helpers.subtitle_tracks(info)
        self.assertEqual(tracks, [{"lang": "en-orig", "label": "English (auto)", "auto": True}])

    def test_no_subtitles(self):
        self.assertEqual(ytdlp_helpers.subtitle_tracks({}), [])

    def test_post_payload_lists_them_with_download_links(self):
        post = {
            "id": "vid", "title": "T", "author": "A", "kind": "video", "cover": None, "images": [], "videos": [],
            "subtitles": [{"lang": "en", "label": "English", "auto": False}],
        }
        view = build_app_post(post, "youtube", PREFS, "https://www.youtube.com/watch?v=vid")
        track = view["subtitles"][0]
        self.assertEqual(track["name"], "youtube_vid_en_subtitles")
        self.assertTrue(track["url"].startswith("/api/subtitle?"))
        self.assertIn("lang=en", track["url"])

    def test_filename_styles_for_subtitles(self):
        kwargs = dict(platform="youtube", post_id="vid", title="My clip", author="Me", kind="subtitle", lang="fr")
        self.assertEqual(build_filename("classic", **kwargs), "youtube_vid_fr_subtitles")
        self.assertEqual(build_filename("title", **kwargs), "My clip (fr subtitles)")


class SubtitleConversion(unittest.TestCase):
    def test_vtt_becomes_numbered_srt_without_tags(self):
        srt = subtitles.vtt_to_srt(VTT)
        self.assertEqual(
            srt,
            "1\n00:00:01,000 --> 00:00:02,500\nHello there & welcome\n\n"
            "2\n00:00:03,000 --> 00:00:04,250\nSecond line\n",
        )

    def test_empty_input_gives_nothing(self):
        self.assertEqual(subtitles.vtt_to_srt("WEBVTT\n\n"), "")


def fake_downloader(extension, content):
    def run(args, timeout):
        folder = os.path.dirname(args[args.index("-o") + 1])
        if content is not None:
            Path(folder, f"sub.en.{extension}").write_text(content, encoding="utf-8")
        return FakeRun()

    return run


class SubtitleRoute(unittest.TestCase):
    URL = "https://www.youtube.com/watch?v=aaaaaaaaaaa"

    def setUp(self):
        self.client = create_app().test_client()

    def get(self, **extra):
        query = {"u": self.URL, "lang": "en", "name": "clip subs", **extra}
        return self.client.get("/api/subtitle", query_string=query)

    def test_vtt_is_served_as_srt(self):
        with mock.patch.object(ytdlp_runner, "run_download", fake_downloader("vtt", VTT)):
            response = self.get()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.mimetype, "application/x-subrip")
        self.assertIn('filename="clip subs.srt"', response.headers["Content-Disposition"])
        self.assertIn("00:00:01,000 --> 00:00:02,500", response.get_data(as_text=True))

    def test_srt_passes_through(self):
        with mock.patch.object(ytdlp_runner, "run_download", fake_downloader("srt", "1\n00:00:01,000 --> 00:00:02,000\nHi\n")):
            response = self.get()
        self.assertEqual(response.status_code, 200)
        self.assertIn("Hi", response.get_data(as_text=True))

    def test_missing_language_is_a_404(self):
        with mock.patch.object(ytdlp_runner, "run_download", fake_downloader("vtt", None)):
            response = self.get()
        self.assertEqual(response.status_code, 404)
        self.assertIn("no subtitles", response.get_data(as_text=True))

    def test_bad_inputs_are_refused(self):
        self.assertEqual(self.get(lang="../etc").status_code, 400)
        refused = self.client.get("/api/subtitle", query_string={"u": "https://example.com/v", "lang": "en"})
        self.assertEqual(refused.status_code, 400)
        refused = self.client.get("/api/subtitle", query_string={"u": "https://www.reddit.com/r/x/comments/1/a/", "lang": "en"})
        self.assertEqual(refused.status_code, 400)

    def test_timeout_is_reported(self):
        def slow(args, timeout):
            raise subprocess.TimeoutExpired("yt-dlp", timeout)

        with mock.patch.object(ytdlp_runner, "run_download", slow):
            response = self.get()
        self.assertEqual(response.status_code, 504)

    def test_temp_files_are_cleaned_up(self):
        from mediadl.services import storage

        before = set(os.listdir(storage.work_dir()))
        with mock.patch.object(ytdlp_runner, "run_download", fake_downloader("vtt", VTT)):
            response = self.get()
            response.get_data()
            response.close()
        leftovers = [name for name in set(os.listdir(storage.work_dir())) - before if name != "jobs"]
        self.assertEqual(leftovers, [])

    def test_job_endpoint_accepts_subtitle_paths(self):
        from mediadl.services import jobs

        self.assertTrue(jobs._valid_path("/api/subtitle?u=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dx&lang=en"))


if __name__ == "__main__":
    unittest.main()
