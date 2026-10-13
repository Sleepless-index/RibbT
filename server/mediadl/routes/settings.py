from flask import Blueprint, jsonify, request

from ..config import SETTINGS_COOKIE, SETTINGS_COOKIE_MAX_AGE
from ..preferences import choices_payload, schema_payload, to_cookie, validate
from .common import current_preferences

bp = Blueprint("settings", __name__)


def _is_https():
    return request.is_secure or request.headers.get("X-Forwarded-Proto") == "https"


@bp.get("/api/settings")
def read_settings():
    return jsonify(settings=current_preferences(), choices=choices_payload(), schema=schema_payload())


@bp.post("/api/settings")
def write_settings():
    settings = validate(request.get_json(silent=True) or {})
    response = jsonify(settings=settings)
    response.set_cookie(
        SETTINGS_COOKIE,
        to_cookie(settings),
        max_age=SETTINGS_COOKIE_MAX_AGE,
        path="/",
        httponly=True,
        samesite="Lax",
        secure=_is_https(),
    )
    return response
