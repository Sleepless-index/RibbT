from flask import Blueprint, jsonify, request, send_file

from ..errors import MediaError
from ..services import jobs

bp = Blueprint("jobs", __name__)

GONE = "That download is no longer on the server. Start it again."


def _error(message, status):
    return jsonify(error=message), status


@bp.post("/api/jobs")
def create():
    payload = request.get_json(silent=True) or {}
    try:
        job = jobs.create(payload.get("path"), payload.get("name"), request.headers.get("Cookie", ""))
    except MediaError as error:
        return _error(error.message, error.status)
    return jsonify(id=job.id), 202


@bp.get("/api/jobs/<job_id>")
def status(job_id):
    job = jobs.get(job_id)
    if job is None:
        return _error(GONE, 404)
    return jsonify(job.snapshot())


@bp.get("/api/jobs/<job_id>/file")
def file(job_id):
    job = jobs.get(job_id)
    if job is None or job.state != "ready" or not job.file.is_file():
        return _error(GONE, 404)
    # conditional=True answers Range requests, which is what lets the app resume a transfer.
    response = send_file(job.file, mimetype=job.mime or "application/octet-stream", conditional=True, etag=False)
    if job.disposition:
        response.headers["Content-Disposition"] = job.disposition
    response.headers["Cache-Control"] = "no-store"
    return response


@bp.delete("/api/jobs/<job_id>")
def remove(job_id):
    return jsonify(removed=jobs.remove(job_id))
