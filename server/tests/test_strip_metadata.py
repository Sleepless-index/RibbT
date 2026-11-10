import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from mediadl.services import ffmpeg  # noqa: E402
from mediadl.services.transforms import audio_transform, video_transform  # noqa: E402


class StripMetadataTests(unittest.TestCase):
    def test_runs_drop_the_sources_tags_and_chapters_after_the_inputs(self):
        with mock.patch.object(ffmpeg.subprocess, "Popen") as popen, mock.patch.object(ffmpeg, "FFMPEG", "ffmpeg"):
            ffmpeg.spawn_local(["in.mp4"], ("-c", "copy", "-f", "mp4", "-"))
        command = popen.call_args.args[0]
        index = command.index("-map_metadata")
        self.assertEqual(command[index + 1], "-1")
        self.assertIn("-map_chapters", command)
        self.assertGreater(index, command.index("-i"))

    def test_transforms_do_not_copy_input_metadata_back(self):
        for transform in (video_transform("mp4", 8_000_000), audio_transform("mp3", "192", {"title": "T"})):
            self.assertNotIn("-map_metadata", transform.args)

    def test_audio_keeps_the_tags_we_set_ourselves(self):
        args = audio_transform("mp3", "192", {"title": "My title", "artist": "Me"}).args
        self.assertIn("title=My title", args)
        self.assertIn("artist=Me", args)


if __name__ == "__main__":
    unittest.main()
