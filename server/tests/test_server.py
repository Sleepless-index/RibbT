"""Tests for the background jobs, cookie import, storage guards and the yt-dlp update check.

Run from the ``server`` folder:  python -m unittest discover -s tests -v
(needs flask and requests; the mux test also needs ffmpeg on PATH and is skipped without it).
"""
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

_ROOT = tempfile.mkdtemp(prefix="ribbt-test-")
os.environ["RIBBT_TMP_DIR"] = os.path.join(_ROOT, "tmp")
os.environ["RIBBT_COOKIES_PATH"] = os.path.join(_ROOT, "cookies.txt")
os.environ.pop("YTDLP_COOKIES", None)
# No bitrate cap, so a plain video download is a straight pass-through (no ffprobe, no network).
_SETTINGS = os.path.join(_ROOT, "settings.json")
os.makedirs(_ROOT, exist_ok=True)
with open(_SETTINGS, "w") as handle:
    handle.write('{"video": {"bitrate": "auto", "container": "auto"}}')
os.environ["MDL_SETTINGS_PATH"] = _SETTINGS

from mediadl import create_app  # noqa: E402
from mediadl.errors import MediaError  # noqa: E402
from mediadl.services import cookies, ffmpeg, jobs, net, storage, ytdlp_update  # noqa: E402
from mediadl.services import delivery  # noqa: E402

VIDEO_URL = "https://i.ytimg.com/clip.mp4"
AUDIO_URL = "https://i.ytimg.com/clip.m4a"


class FakeResponse:
    """Just enough of requests.Response for the download code, with Range support."""

    def __init__(self, body, range_header=None, status=None, content_type="video/mp4"):
        self.final_url = ""
        start, end = 0, len(body) - 1
        self.status_code = status or 200
        self.headers = {"Content-Type": content_type}
        if range_header and status is None:
            lo, _, hi = range_header.split("=")[1].partition("-")
            start = int(lo)
            if start >= len(body):
                self.status_code = 416
                body = b""
            else:
                end = min(int(hi) if hi else len(body) - 1, len(body) - 1)
                self.status_code = 206
                self.headers["Content-Range"] = f"bytes {start}-{end}/{len(body)}"
                body = body[start:end + 1]
        self.headers["Content-Length"] = str(len(body))
        self._body = body
        self.raw = self

    def stream(self, size, decode_content=False):
        for i in range(0, len(self._body), size):
            yield self._body[i:i + size]

    def iter_content(self, size):
        return self.stream(size)

    def close(self):
        pass


def fake_open_media(files, slow=0.0):
    def opener(url, extra=None, cache=None, timeout=30):
        if url not in files:
            return FakeResponse(b"", status=403)
        if slow:
            time.sleep(slow)
        return FakeResponse(files[url], (extra or {}).get("Range"))
    return opener


def wait_for(client, job_id, states, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        data = client.get(f"/api/jobs/{job_id}").get_json()
        if data["state"] in states:
            return data
        time.sleep(0.05)
    raise AssertionError(f"job stuck: {data}")


class JobTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = create_app()
        cls.client = cls.app.test_client()

    def run_job(self, files, path, name="x"):
        with mock.patch.object(net, "open_media", fake_open_media(files)), \
                mock.patch.object(delivery, "open_media", fake_open_media(files)):
            response = self.client.post("/api/jobs", json={"path": path, "name": name})
            self.assertEqual(response.status_code, 202)
            job_id = response.get_json()["id"]
            return job_id, wait_for(self.client, job_id, ("ready", "error", "cancelled"))

    def test_passthrough_job_reports_progress_and_serves_file_with_resume(self):
        body = os.urandom(3 * 1024 * 1024 + 123)
        job_id, data = self.run_job({VIDEO_URL: body}, f"/api/video?u={VIDEO_URL}&name=clip")
        self.assertEqual(data["state"], "ready", data)
        self.assertEqual(data["progress"], 1.0)
        self.assertEqual(data["size"], len(body))

        full = self.client.get(f"/api/jobs/{job_id}/file")
        self.assertEqual(full.status_code, 200)
        self.assertEqual(full.data, body)
        self.assertIn("clip", full.headers["Content-Disposition"])

        resumed = self.client.get(f"/api/jobs/{job_id}/file", headers={"Range": "bytes=1000-"})
        self.assertEqual(resumed.status_code, 206)
        self.assertEqual(resumed.data, body[1000:])

        self.assertTrue(self.client.delete(f"/api/jobs/{job_id}").get_json()["removed"])
        self.assertEqual(self.client.get(f"/api/jobs/{job_id}").status_code, 404)
        self.assertEqual(self.client.get(f"/api/jobs/{job_id}/file").status_code, 404)

    def test_upstream_refusal_becomes_a_readable_error(self):
        _, data = self.run_job({}, f"/api/video?u={VIDEO_URL}&name=clip")
        self.assertEqual(data["state"], "error")
        self.assertTrue(data["error"])

    def test_only_download_endpoints_are_accepted(self):
        for path in ("/api/jobs", "/api/settings", "https://evil.example/api/video", "/api/media?u=x&inline=1"):
            response = self.client.post("/api/jobs", json={"path": path})
            self.assertEqual(response.status_code, 400, path)

    def test_short_body_is_reported_instead_of_saved_truncated(self):
        class Short(FakeResponse):
            def stream(self, size, decode_content=False):
                yield self._body[: len(self._body) // 2]

        def opener(url, extra=None, cache=None, timeout=30):
            return Short(os.urandom(100_000))

        with mock.patch.object(net, "open_media", opener), mock.patch.object(delivery, "open_media", opener):
            job_id = self.client.post("/api/jobs", json={"path": f"/api/video?u={VIDEO_URL}&name=c"}).get_json()["id"]
            data = wait_for(self.client, job_id, ("ready", "error"))
        self.assertEqual(data["state"], "error")
        self.assertIn("interrupted", data["error"])

    def test_cancel_stops_a_running_job_and_cleans_up(self):
        body = os.urandom(40 * 1024 * 1024)
        with mock.patch.object(net, "open_media", fake_open_media({VIDEO_URL: body, AUDIO_URL: b"a" * 1000}, slow=0.3)), \
                mock.patch.object(delivery, "open_media", fake_open_media({VIDEO_URL: body, AUDIO_URL: b"a" * 1000}, slow=0.3)):
            job_id = self.client.post(
                "/api/jobs",
                json={"path": f"/api/video?u={VIDEO_URL}&audio={AUDIO_URL}&name=c"},
            ).get_json()["id"]
            time.sleep(0.5)
            self.client.delete(f"/api/jobs/{job_id}")
            deadline = time.time() + 10
            while time.time() < deadline and jobs.get(job_id) is not None:
                time.sleep(0.05)
        self.assertIsNone(jobs.get(job_id))
        self.assertFalse((storage.jobs_dir() / job_id).exists())

    @unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg not installed")
    def test_mux_job_converts_and_adopts_the_output(self):
        work = Path(_ROOT) / "media"
        work.mkdir(exist_ok=True)
        video, audio = work / "v.mp4", work / "a.m4a"
        run = lambda *args: subprocess.run(["ffmpeg", "-loglevel", "error", "-y", *args], check=True)
        run("-f", "lavfi", "-i", "testsrc=duration=2:size=160x120:rate=10", "-pix_fmt", "yuv420p", str(video))
        run("-f", "lavfi", "-i", "sine=duration=2", "-c:a", "aac", str(audio))
        before = set(storage.work_dir().glob("mdl_*"))
        job_id, data = self.run_job(
            {VIDEO_URL: video.read_bytes(), AUDIO_URL: audio.read_bytes()},
            f"/api/video?u={VIDEO_URL}&audio={AUDIO_URL}&name=clip",
        )
        self.assertEqual(data["state"], "ready", data)
        self.assertEqual(data["mime"], "video/mp4")
        saved = self.client.get(f"/api/jobs/{job_id}/file").data
        self.assertGreater(len(saved), 1000)
        probe = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", "-"],
            input=saved, capture_output=True,
        )
        self.assertEqual(sorted(probe.stdout.decode().split()), ["audio", "video"])
        # Source and output temp files are gone once the job has taken the result.
        self.assertEqual(set(storage.work_dir().glob("mdl_*")), before)

    def test_finished_downloads_survive_a_restart_and_unfinished_ones_do_not(self):
        body = os.urandom(50_000)
        job_id, data = self.run_job({VIDEO_URL: body}, f"/api/video?u={VIDEO_URL}&name=keep")
        self.assertEqual(data["state"], "ready")
        orphan = storage.jobs_dir() / "deadbeefdeadbeef"
        orphan.mkdir()
        (orphan / "file").write_bytes(b"partial")
        jobs._jobs.clear()
        jobs.restore()
        self.assertIsNotNone(jobs.get(job_id))
        self.assertFalse(orphan.exists())
        self.assertEqual(self.client.get(f"/api/jobs/{job_id}/file").data, body)
        jobs.remove(job_id)


class StorageTests(unittest.TestCase):
    def test_require_space_refuses_with_a_plain_message(self):
        with mock.patch.object(storage, "free_bytes", return_value=100 * 1024 * 1024):
            with self.assertRaises(MediaError) as caught:
                storage.require_space(500 * 1024 * 1024)
        self.assertEqual(caught.exception.status, 507)
        self.assertIn("Not enough free storage", caught.exception.message)
        with mock.patch.object(storage, "free_bytes", return_value=10 * 1024 ** 3):
            storage.require_space(500 * 1024 * 1024)

    def test_download_refuses_before_filling_the_disk(self):
        body = os.urandom(1024 * 1024)
        target = storage.temp_path(".src")
        try:
            with mock.patch.object(net, "open_media", fake_open_media({VIDEO_URL: body})), \
                    mock.patch.object(storage, "free_bytes", return_value=1024):
                with self.assertRaises(MediaError) as caught:
                    net.download_to_file(VIDEO_URL, target)
            self.assertEqual(caught.exception.status, 507)
        finally:
            os.unlink(target)

    def test_startup_cleanup_removes_leftovers_only(self):
        old = storage.temp_path(".src")
        os.utime(old, (0, 0))
        fresh = storage.temp_path(".src")
        other = storage.work_dir() / "unrelated.txt"
        other.write_text("keep")
        try:
            storage.startup_cleanup()  # shared-machine rules: only files older than an hour
            self.assertFalse(os.path.exists(old))
            self.assertTrue(os.path.exists(fresh))
            with mock.patch.dict(os.environ, {"RIBBT_EMBEDDED": "1"}):
                storage.startup_cleanup()  # inside the app: everything from the last run
            self.assertFalse(os.path.exists(fresh))
            self.assertTrue(other.exists())
        finally:
            other.unlink()


COOKIE_FILE = "\n".join([
    "# Netscape HTTP Cookie File",
    "# comment",
    ".youtube.com\tTRUE\t/\tTRUE\t2000000000\tLOGIN_INFO\tsecret-value-1",
    "#HttpOnly_.google.com\tTRUE\t/\tTRUE\t2000000000\tSID\tsecret-value-2",
    ".bank.example\tTRUE\t/\tTRUE\t2000000000\tsession\tsecret-bank",
    "www.reddit.com\tFALSE\t/\tFALSE\t0\tempty",
    "",
])


class CookieTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = create_app().test_client()

    def tearDown(self):
        cookies.remove()

    def test_import_keeps_only_supported_sites_and_never_returns_values(self):
        response = self.client.post("/api/cookies", data=COOKIE_FILE, content_type="text/plain")
        self.assertEqual(response.status_code, 200)
        data = response.get_json()
        self.assertTrue(data["present"])
        self.assertEqual(data["count"], 3)
        self.assertEqual(data["domains"], ["google.com", "www.reddit.com", "youtube.com"])
        self.assertNotIn("secret", response.get_data(as_text=True))
        stored = cookies.store_path().read_text()
        self.assertNotIn("bank.example", stored)
        self.assertTrue(stored.startswith("# Netscape HTTP Cookie File"))
        self.assertEqual(cookies.active_path(), str(cookies.store_path()))
        if os.name == "posix":
            self.assertEqual(cookies.store_path().stat().st_mode & 0o077, 0)

    def test_rejects_files_that_are_not_cookies(self):
        for body, fragment in (("hello world", "doesn’t look like"), (".bank.example\tTRUE\t/\tTRUE\t0\ta\tb", "no cookies for the sites")):
            response = self.client.post("/api/cookies", data=body, content_type="text/plain")
            self.assertEqual(response.status_code, 400)
            self.assertIn(fragment, response.get_json()["error"])
        self.assertFalse(self.client.get("/api/cookies").get_json()["present"])

    def test_remove_and_remote_clients(self):
        self.client.post("/api/cookies", data=COOKIE_FILE)
        remote = {"environ_overrides": {"REMOTE_ADDR": "192.168.1.20"}}
        self.assertEqual(self.client.post("/api/cookies", data=COOKIE_FILE, **remote).status_code, 403)
        self.assertEqual(self.client.delete("/api/cookies", **remote).status_code, 403)
        self.assertTrue(self.client.get("/api/cookies", **remote).get_json()["present"])
        self.assertFalse(self.client.delete("/api/cookies").get_json()["present"])
        self.assertIsNone(cookies.active_path())

    def test_yt_dlp_is_given_the_imported_file(self):
        from mediadl.adapters import ytdlp

        self.client.post("/api/cookies", data=COOKIE_FILE)
        seen = {}

        def fake_run(command, timeout):
            seen["command"] = command
            return subprocess.CompletedProcess(command, 0, "{}\n", "")

        with mock.patch.object(ytdlp.ytdlp_runner, "run", fake_run):
            ytdlp.dump_info("https://youtu.be/x", "YouTube")
        index = seen["command"].index("--cookies")
        self.assertEqual(seen["command"][index + 1], str(cookies.store_path()))


def pypi_payload(sha256, host="files.pythonhosted.org", size=100):
    return {"releases": {"2099.1.1": [{
        "filename": "yt_dlp-2099.1.1-py3-none-any.whl",
        "url": f"https://{host}/packages/yt_dlp-2099.1.1-py3-none-any.whl",
        "upload_time_iso_8601": "2099-01-01T00:00:00Z",
        "digests": {"sha256": sha256} if sha256 is not None else {},
        "size": size,
    }]}}


class WheelStreamer:
    def __init__(self, data):
        self.data = data

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def raise_for_status(self):
        pass

    def iter_content(self, size):
        for i in range(0, len(self.data), size):
            yield self.data[i:i + size]


class UpdateChecksumTests(unittest.TestCase):
    def wheel(self, **files):
        buffer = io.BytesIO()
        import zipfile

        with zipfile.ZipFile(buffer, "w") as archive:
            for name, text in files.items():
                archive.writestr(name, text)
        return buffer.getvalue()

    def test_pick_wheel_returns_the_published_hash(self):
        version, url, digest, size = ytdlp_update._pick_wheel(pypi_payload("ab" * 32), "stable")
        self.assertEqual((version, digest, size), ("2099.1.1", "ab" * 32, 100))

    def test_matching_hash_is_accepted(self):
        data = os.urandom(5000)
        digest = hashlib.sha256(data).hexdigest()
        with mock.patch.object(ytdlp_update.requests, "get", return_value=WheelStreamer(data)):
            _, url, sha, size = ytdlp_update._pick_wheel(pypi_payload(digest), "stable")
            self.assertEqual(ytdlp_update._download_wheel(url, sha, size), data)

    def test_tampered_download_is_refused(self):
        digest = hashlib.sha256(b"the real wheel").hexdigest()
        with mock.patch.object(ytdlp_update.requests, "get", return_value=WheelStreamer(b"something else")):
            _, url, sha, size = ytdlp_update._pick_wheel(pypi_payload(digest), "stable")
            with self.assertRaises(ytdlp_update.UpdateError) as caught:
                ytdlp_update._download_wheel(url, sha, size)
        self.assertIn("checksum", str(caught.exception))

    def test_missing_hash_unexpected_host_and_oversize_are_refused(self):
        for payload, fragment in (
            (pypi_payload(None), "didn’t publish a checksum"),
            (pypi_payload("ab" * 32, host="evil.example"), "unexpected download address"),
            (pypi_payload("ab" * 32, size=10 ** 9), "larger than expected"),
        ):
            _, url, sha, size = ytdlp_update._pick_wheel(payload, "stable")
            with mock.patch.object(ytdlp_update.requests, "get", side_effect=AssertionError("must not download")):
                with self.assertRaises(ytdlp_update.UpdateError) as caught:
                    ytdlp_update._download_wheel(url, sha, size)
            self.assertIn(fragment, str(caught.exception))

    def test_embedded_update_installs_only_after_the_hash_checks_out(self):
        store = Path(_ROOT) / "ytdlp-store"
        wheel = self.wheel(**{"yt_dlp/__init__.py": "", "yt_dlp/version.py": "__version__ = '2099.1.1'\n"})
        digest = hashlib.sha256(wheel).hexdigest()

        def run(sha, payload_bytes):
            meta = mock.Mock(json=lambda: pypi_payload(sha, size=len(payload_bytes)))
            calls = [meta, WheelStreamer(payload_bytes)]
            with mock.patch.dict(os.environ, {"RIBBT_YTDLP_DIR": str(store)}), \
                    mock.patch.object(ytdlp_update.requests, "get", side_effect=lambda *a, **k: calls.pop(0)), \
                    mock.patch.object(ytdlp_update, "_module_version", return_value="2000.1.1"), \
                    mock.patch.object(ytdlp_update, "activate", return_value="2099.1.1"):
                return ytdlp_update._embedded_update("stable")

        with self.assertRaises(ytdlp_update.UpdateError):
            run(digest, wheel + b"tampered")
        self.assertFalse((store / "2099.1.1").exists())
        run(digest, wheel)
        self.assertTrue((store / "2099.1.1" / "yt_dlp" / "version.py").is_file())
        self.assertEqual((store / "current.txt").read_text(), "2099.1.1")


class PortTests(unittest.TestCase):
    def test_falls_back_to_a_free_port_when_the_preferred_one_is_taken(self):
        import socket

        sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "python"))
        import ribbt_server

        blocker = socket.socket()
        blocker.bind(("127.0.0.1", 0))
        blocker.listen(1)
        taken = blocker.getsockname()[1]
        try:
            for preferred in (taken, 0):
                server = ribbt_server._bind(create_app(), preferred)
                try:
                    self.assertNotEqual(server.server_port, taken)
                    self.assertGreater(server.server_port, 0)
                finally:
                    server.server_close()
        finally:
            blocker.close()


if __name__ == "__main__":
    unittest.main()
