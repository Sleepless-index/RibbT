import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


class Recorder:
    def __init__(self):
        self.fractions = []

    def set_fraction(self, value):
        self.fractions.append(value)


@unittest.skipUnless(__import__("shutil").which("ffmpeg"), "ffmpeg not installed")
class ConversionProgressTests(unittest.TestCase):
    def test_progress_file_is_read_and_reaches_the_job(self):
        from mediadl.services import delivery, ffmpeg, progress
        with tempfile.TemporaryDirectory() as folder:
            source, out, log = (os.path.join(folder, n) for n in ("src.mp4", "out.mp4", "p.txt"))
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                            "testsrc2=size=1280x720:rate=30:duration=6", "-pix_fmt", "yuv420p", source], check=True)
            self.assertAlmostEqual(ffmpeg.probe_file_duration(source), 6.0, delta=0.3)

            process = ffmpeg.spawn_local(
                [source], ["-progress", log, "-nostats", "-c:v", "libx264", "-preset", "slow", "-crf", "12", out],
                stdout=subprocess.DEVNULL,
            )
            tail = ffmpeg.StderrTail(process)
            recorder = Recorder()
            with progress.bind(recorder):
                delivery._wait(process, tail, log, 6.0)
            self.assertEqual(process.returncode, 0)
            self.assertTrue(recorder.fractions, "no progress was reported")
            self.assertTrue(all(0 <= f <= 1.1 for f in recorder.fractions))
            self.assertEqual(recorder.fractions, sorted(recorder.fractions))

    def test_reader_handles_missing_and_empty_files(self):
        from mediadl.services import ffmpeg
        self.assertIsNone(ffmpeg.read_progress_seconds("/nonexistent/x"))
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
            f.write("frame=1\nout_time_us=N/A\n")
        try:
            self.assertIsNone(ffmpeg.read_progress_seconds(f.name))
            Path(f.name).write_text("out_time_us=1500000\nprogress=continue\nout_time_us=2500000\nprogress=continue\n")
            self.assertAlmostEqual(ffmpeg.read_progress_seconds(f.name), 2.5)
        finally:
            os.unlink(f.name)

    def test_job_fraction_only_moves_forward_and_is_capped(self):
        from mediadl.services.jobs import Job
        job = Job("abc", Path(tempfile.gettempdir()) / "x", "n", "")
        job.set_phase("converting")
        job.set_fraction(0.4)
        job.set_fraction(0.2)
        self.assertEqual(job._progress, 0.4)
        job.set_fraction(5)
        self.assertEqual(job._progress, 0.99)

    def test_job_says_what_the_conversion_is_for(self):
        from mediadl.services.jobs import Job
        job = Job("abc", Path(tempfile.gettempdir()) / "x", "n", "")
        job.set_phase("converting", "Extracting the audio")
        self.assertEqual(job.snapshot()["detail"], "Extracting the audio")
        job.set_phase("downloading")
        self.assertEqual(job.snapshot()["detail"], "")

    def test_shipped_defaults_have_no_bitrate_cap(self):
        import json
        shipped = json.loads((Path(__file__).resolve().parents[1] / "config" / "settings.json").read_text())
        self.assertEqual(shipped["video"]["bitrate"], "auto")


if __name__ == "__main__":
    unittest.main()
