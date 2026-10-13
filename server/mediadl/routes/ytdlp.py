from flask import Blueprint, jsonify, request

from ..services import ytdlp_update

bp = Blueprint("ytdlp", __name__)


@bp.get("/api/ytdlp")
def ytdlp_info():
    return jsonify(version=ytdlp_update.installed_version(), channels=list(ytdlp_update.CHANNELS))


@bp.post("/api/ytdlp/update")
def ytdlp_update_now():
    payload = request.get_json(silent=True) or {}
    try:
        result = ytdlp_update.update(payload.get("channel") or "stable")
    except ytdlp_update.UpdateError as failure:
        return jsonify(error=str(failure)), 500
    return jsonify(result)
