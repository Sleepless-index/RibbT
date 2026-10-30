"""Lets long-running work report progress to the background download that asked for it.

A job binds itself to the worker thread while it runs. The download and conversion code calls
``report`` / ``set_phase`` without knowing whether anyone is listening; outside a job they do nothing.
"""
import contextvars

_current = contextvars.ContextVar("ribbt_job", default=None)


class JobCancelled(Exception):
    """Raised inside the worker when the app asked to cancel the download."""


def current():
    return _current.get()


class bind:
    def __init__(self, job):
        self._job = job
        self._token = None

    def __enter__(self):
        self._token = _current.set(self._job)
        return self._job

    def __exit__(self, *exc_info):
        _current.reset(self._token)
        return False


def report(key, done, total=None):
    job = _current.get()
    if job is not None:
        job.report(key, done, total)


def set_phase(name, detail=""):
    job = _current.get()
    if job is not None:
        job.set_phase(name, detail)


def attach(process):
    """Hand a running ffmpeg to the job so cancelling it can stop the process."""
    job = _current.get()
    if job is not None:
        job.attach(process)


def set_fraction(fraction):
    """Report how far the current conversion is (0..1) once the work itself can be measured."""
    job = _current.get()
    if job is not None:
        job.set_fraction(fraction)
