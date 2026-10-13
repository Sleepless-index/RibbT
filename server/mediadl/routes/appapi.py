from flask import Blueprint, jsonify, request

from ..adapters import find_link, pick_adapter, platform_catalog
from ..services import history
from ..services.appview import build_app_post
from .common import current_preferences
from .parse import ParseFailure, run_parse

bp = Blueprint("app_api", __name__)


@bp.get("/api/platforms")
def platforms():
    return jsonify(platforms=platform_catalog())


@bp.post("/api/app/parse")
def app_parse():
    try:
        post, adapter, link = run_parse(request.get_json(silent=True) or {})
    except ParseFailure as failure:
        return jsonify(error=failure.message), failure.status
    return jsonify(build_app_post(post, adapter.name, current_preferences(), link))


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
