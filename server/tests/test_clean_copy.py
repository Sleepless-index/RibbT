import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from mediadl import create_app  # noqa: E402
from mediadl.services import delivery  # noqa: E402
from mediadl.services.transforms import remux_transform  # noqa: E402


class CleanCopyTests(unittest.TestCase):
    def test_default_video_download_is_rewrapped_not_passed_through(self):
        client = create_app().test_client()
        with mock.patch("mediadl.routes.media.require_media_url", side_effect=lambda u: u), \
                mock.patch("mediadl.routes.media.clean_copy", return_value=("ok", 200)) as clean, \
                mock.patch("mediadl.routes.media.passthrough") as plain:
            client.get("/api/video", query_string={"u": "https://video.twimg.com/a.mp4", "name": "x"})
        self.assertTrue(clean.called)
        self.assertFalse(plain.called)

    def test_remux_copies_streams_without_encoding(self):
        args = remux_transform("mp4").args
        self.assertEqual(args[:2], ("-c", "copy"))
        self.assertIn("+faststart", args)

    def test_without_ffmpeg_the_file_is_passed_through(self):
        with mock.patch.object(delivery.ffmpeg, "FFMPEG", None), mock.patch.object(delivery, "passthrough", return_value="raw") as plain:
            self.assertEqual(delivery.clean_copy("https://x/a.mp4", "n", "mp4"), "raw")
        self.assertTrue(plain.called)


if __name__ == "__main__":
    unittest.main()
