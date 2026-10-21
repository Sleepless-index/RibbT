import logging

from flask import Blueprint, jsonify, request

from ..adapters import find_link, pick_adapter, platform_catalog
from ..services import history
from ..services.appview import build_app_playlist, build_app_post
from .common import current_preferences
from .parse import ParseFailure, run_parse

log = logging.getLogger(__name__)

bp = Blueprint("app_api", __name__)


def _playlist_response(adapter, link):
    try:
        playlist = adapter.extract_playlist(link)
    except ValueError as exc:
        return jsonify(error=str(exc)), 400
    except Exception as exc:
        log.exception("playlist failed for %s", adapter.name)
        return jsonify(error=f"Could not read that playlist: {exc.__class__.__name__}"), 502
    return jsonify(build_app_playlist(playlist, adapter.name, link))


@bp.get("/api/platforms")
def platforms():
    return jsonify(platforms=platform_catalog())


@bp.post("/api/app/parse")
def app_parse():
    payload = request.get_json(silent=True) or {}
    link = find_link(payload.get("url", ""))
    adapter = pick_adapter(link)
    if adapter is not None and adapter.is_playlist(link):
        return _playlist_response(adapter, link)
    try:
        post, adapter, link = run_parse(payload)
    except ParseFailure as failure:
        return jsonify(error=failure.message), failure.status
    result = build_app_post(post, adapter.name, current_preferences(), link)
    result["type"] = "post"
    result["playlist"] = link if adapter.has_playlist(link) else None
    return jsonify(result)


@bp.post("/api/app/playlist")
def app_playlist():
    link = find_link((request.get_json(silent=True) or {}).get("url", ""))
    adapter = pick_adapter(link)
    if adapter is None:
        return jsonify(error="Not a supported link."), 400
    if not adapter.has_playlist(link) and not adapter.is_playlist(link):
        return jsonify(error="That link isn’t a playlist."), 400
    return _playlist_response(adapter, link)


@bp.get("/api/history")
def history_list():
    return jsonify(items=history.list_items())


@bp.post("/api/history")
def history_add():
    payload = request.get_json(silent=True) or {}
    link = find_link(payload.get("link", ""))
    adapter = pick_adapter(link)
    if not adapter:
        return jsonify(error="Not a supported link."), 400
    entry = history.add_item({**payload, "link": link, "platform": adapter.name})
    return jsonify(entry)


@bp.delete("/api/history/<item_id>")
def history_remove(item_id):
    return jsonify(removed=history.remove_item(item_id))


@bp.delete("/api/history")
def history_clear():
    history.clear_items()
    return jsonify(items=[])
