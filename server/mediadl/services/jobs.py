"""Downloads that run in the background and report progress.

The app used to wait on one long HTTP request, so it could show no progress, could not pick up a
half-finished transfer, and lost the download whenever that request died. Now it creates a job,
polls for progress, and collects the finished file separately (with Range support, so an
interrupted transfer carries on where it stopped).

A job runs the same request the app would have made (``/api/video?...`` and friends) through the
Flask app itself, so every conversion rule lives in one place. Finished files stay on disk for a
while, and survive a server restart, until the app has collected them.
"""
import json
import logging
import os
import shutil
import threading
import time
import uuid
from urllib.parse import urlsplit

from ..errors import MediaError
from . import ffmpeg, progress, storage

log = logging.getLogger(__name__)

ALLOWED_PATHS = ("/api/video", "/api/audio", "/api/media", "/api/subtitle")
MAX_ACTIVE = 3
MAX_JOBS = 40
READY_TTL_SECONDS = 6 * 60 * 60
FAILED_TTL_SECONDS = 15 * 60
REAP_INTERVAL_SECONDS = 5 * 60
COPY_CHUNK_BYTES = 256 * 1024
REPORT_INTERVAL_SECONDS = 0.2
FILE_NAME = "file"
META_NAME = "job.json"

_jobs = {}
_registry_lock = threading.Lock()
_slots = threading.Semaphore(MAX_ACTIVE)
_app = None
_reaper_started = False


class Job:
    def __init__(self, job_id, path, name, cookie):
        self.id = job_id
        self.path = path
        self.name = name
        self.cookie = cookie
        self.state = "queued"  # queued, running, ready, error, cancelled
        self.phase = "queued"  # queued, downloading, converting
        self.error = None
        self.status = 0
        self.created = time.time()
        self.finished = None
        self.dir = storage.jobs_dir() / job_id
        self.mime = None
        self.disposition = None
        self.size = 0
        self.discard = False
        self.cancelled = threading.Event()
        self._lock = threading.Lock()
        self._sources = {}
        self._progress = None
        self._last_report = 0.0
        self._process = None
        self._adopted = None

    @property
    def file(self):
        return self.dir / FILE_NAME

    # -- called from the code doing the work ---------------------------------------------------

    def report(self, key, done, total=None):
        if self.cancelled.is_set():
            raise progress.JobCancelled()
        now = time.monotonic()
        finished = bool(total) and done >= total
        if not finished and now - self._last_report < REPORT_INTERVAL_SECONDS:
            return
        self._last_report = now
        with self._lock:
            self._sources[key] = (done, total)
            known = [(d, t) for d, t in self._sources.values() if t]
            total_sum = sum(t for _, t in known)
            if total_sum:
                fraction = min(0.99, sum(d for d, _ in known) / total_sum)
                # Never move backwards when a second source (the audio track) turns up.
                self._progress = max(self._progress or 0.0, fraction)

    def set_phase(self, name):
        if self.cancelled.is_set():
            raise progress.JobCancelled()
        with self._lock:
            self.phase = name
            # Converting has no measurable progress; the app shows an indeterminate bar.
            self._progress = None if name == "converting" else self._progress

    def attach(self, process):
        self._process = process
        if self.cancelled.is_set():
            ffmpeg.stop(process)

    def adopt(self, path):
        """The worker made the finished file itself (a conversion); keep it as the result."""
        self._adopted = path

    def cancel(self):
        self.cancelled.set()
        process = self._process
        if process is not None:
            ffmpeg.stop(process)

    # -- reporting -----------------------------------------------------------------------------

    def snapshot(self):
        with self._lock:
            done = sum(d for d, _ in self._sources.values())
            total = sum(t for _, t in self._sources.values() if t) or None
            fraction = 1.0 if self.state == "ready" else self._progress
            return {
                "id": self.id,
                "state": self.state,
                "phase": self.phase,
                "progress": fraction,
                "bytes": done,
                "total": total,
                "size": self.size,
                "mime": self.mime,
                "error": self.error,
                "status": self.status,
            }

    def fail(self, message, status=500):
        self.state = "error"
        self.error = message
        self.status = status
        self.finished = time.time()

    def persist(self):
        try:
            self.dir.mkdir(parents=True, exist_ok=True)
            meta = {
                "id": self.id, "state": self.state, "name": self.name, "mime": self.mime,
                "disposition": self.disposition, "size": self.size, "created": self.created,
                "finished": self.finished,
            }
            temp = self.dir / (META_NAME + ".tmp")
            temp.write_text(json.dumps(meta), encoding="utf-8")
            temp.replace(self.dir / META_NAME)
        except OSError:
            log.exception("could not save job %s", self.id)


# -- running a job -----------------------------------------------------------------------------


def _copy_response(job, response):
    """Stream a response body into the job's file, checking it arrived whole."""
    try:
        expected = int(response.headers.get("Content-Length") or 0) or None
    except ValueError:
        expected = None
    if expected:
        storage.require_space(expected)
    written = 0
    with open(job.file, "wb") as out:
        for chunk in response.iter_encoded():
            if job.cancelled.is_set():
                raise progress.JobCancelled()
            out.write(chunk)
            written += len(chunk)
            job.report("body", written, expected)
    if expected and written != expected:
        raise MediaError("The download was interrupted before it finished. Try again.", 502)
    if written == 0:
        raise MediaError("The media server sent an empty file.", 502)
    return written


def _produce(job):
    job.dir.mkdir(parents=True, exist_ok=True)
    headers = {"Cookie": job.cookie} if job.cookie else {}
    with progress.bind(job):
        response = _app.test_client().get(job.path, headers=headers, buffered=False)
        try:
            if response.status_code >= 400:
                text = response.get_data(as_text=True).strip()
                job.fail(text[:1200] or f"The server answered {response.status_code}.", response.status_code)
                return
            job.mime = (response.mimetype or "application/octet-stream").lower()
            job.disposition = response.headers.get("Content-Disposition")
            if job._adopted:
                os.replace(job._adopted, job.file)
                job._adopted = None
                job.size = job.file.stat().st_size
            else:
                job.size = _copy_response(job, response)
        finally:
            response.close()
            if job._adopted:  # produced but never taken (cancelled or failed)
                try:
                    os.unlink(job._adopted)
                except OSError:
                    pass
    job.state = "ready"
    job.finished = time.time()


def _worker(job):
    with _slots:
        if job.cancelled.is_set():
            job.state = "cancelled"
        else:
            job.state = "running"
            job.phase = "downloading"
            try:
                _produce(job)
            except progress.JobCancelled:
                job.state = "cancelled"
            except MediaError as exc:
                job.fail(exc.message, exc.status)
            except Exception:  # noqa: BLE001 - anything else must still end the job cleanly
                log.exception("job %s failed", job.id)
                job.fail("The download failed on the server.", 500)
    if job.state == "ready":
        job.persist()
        return
    # Failed or cancelled: the half-written file is of no use to anyone.
    shutil.rmtree(job.dir, ignore_errors=True)
    if job.discard:
        with _registry_lock:
            _jobs.pop(job.id, None)


# -- the registry the routes talk to -----------------------------------------------------------


def _valid_path(path):
    if not isinstance(path, str) or not path.startswith("/") or len(path) > 8000:
        return False
    parts = urlsplit(path)
    return parts.path in ALLOWED_PATHS and "inline=1" not in parts.query.split("&")


def create(path, name, cookie):
    if not _valid_path(path):
        raise MediaError("That isn’t something the server can download.", 400)
    storage.require_space(0)
    with _registry_lock:
        busy = [job for job in _jobs.values() if job.state in ("queued", "running")]
        if len(busy) >= MAX_JOBS:
            raise MediaError("Too many downloads at once. Wait for one to finish.", 503)
        job = Job(uuid.uuid4().hex[:16], path, str(name or "")[:200], cookie or "")
        _jobs[job.id] = job
    threading.Thread(target=_worker, args=(job,), name=f"ribbt-job-{job.id}", daemon=True).start()
    return job


def get(job_id):
    with _registry_lock:
        return _jobs.get(job_id)


def remove(job_id):
    """Cancel a running job, or throw away a finished one. Returns False when it is unknown."""
    with _registry_lock:
        job = _jobs.get(job_id)
        if job is None:
            return False
        if job.state in ("queued", "running"):
            job.discard = True
        else:
            _jobs.pop(job_id, None)
    if job.discard:
        job.cancel()
    else:
        shutil.rmtree(job.dir, ignore_errors=True)
    return True


# -- housekeeping ------------------------------------------------------------------------------


def restore():
    """After a restart: keep finished downloads nobody collected yet, drop everything else."""
    root = storage.jobs_dir()
    kept = 0
    for folder in root.iterdir():
        if not folder.is_dir():
            continue
        try:
            meta = json.loads((folder / META_NAME).read_text(encoding="utf-8"))
            file = folder / FILE_NAME
            fresh = time.time() - float(meta.get("finished") or 0) < READY_TTL_SECONDS
            if meta.get("state") != "ready" or not file.is_file() or not fresh:
                raise ValueError("not a finished download")
            job = Job(meta["id"], "", meta.get("name") or "", "")
            job.dir = folder
            job.state, job.phase = "ready", "downloading"
            job.mime, job.disposition = meta.get("mime"), meta.get("disposition")
            job.size, job.created, job.finished = file.stat().st_size, meta.get("created") or 0, meta.get("finished")
            with _registry_lock:
                _jobs[job.id] = job
            kept += 1
        except (OSError, ValueError, KeyError, TypeError):
            shutil.rmtree(folder, ignore_errors=True)
    if kept:
        log.info("kept %d finished download(s) from before the restart", kept)


def reap():
    now = time.time()
    expired = []
    with _registry_lock:
        for job in list(_jobs.values()):
            if job.state == "ready" and job.finished and now - job.finished > READY_TTL_SECONDS:
                expired.append(job)
            elif job.state in ("error", "cancelled") and job.finished and now - job.finished > FAILED_TTL_SECONDS:
                expired.append(job)
        for job in expired:
            _jobs.pop(job.id, None)
    for job in expired:
        shutil.rmtree(job.dir, ignore_errors=True)
    storage.sweep(storage.MAX_TEMP_AGE_SECONDS)


def _reap_forever():
    while True:
        time.sleep(REAP_INTERVAL_SECONDS)
        try:
            reap()
        except Exception:  # noqa: BLE001 - housekeeping must never take the server down
            log.exception("job cleanup failed")


def init(app):
    """Called once when the app is created: clean up after the last run and start the janitor."""
    global _app, _reaper_started
    _app = app
    storage.startup_cleanup()
    restore()
    if not _reaper_started:
        _reaper_started = True
        threading.Thread(target=_reap_forever, name="ribbt-reaper", daemon=True).start()
