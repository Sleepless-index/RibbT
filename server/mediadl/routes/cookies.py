from flask import Blueprint, jsonify, request

from ..services import cookies

bp = Blueprint("cookies", __name__)

LOOPBACK = {"127.0.0.1", "::1"}


def _error(message, status):
    return jsonify(error=message), status


def _local_only():
    if request.remote_addr not in LOOPBACK:
        return _error("Cookies can only be imported on the device that runs the server.", 403)
    return None


@bp.get("/api/cookies")
def cookies_info():
    return jsonify(cookies.info())


@bp.post("/api/cookies")
def cookies_import():
    refused = _local_only()
    if refused:
        return refused
    if (request.content_length or 0) > cookies.MAX_BYTES:
        return _error("That file is too big to be a cookies.txt.", 413)
    text = request.get_data(cache=False, as_text=True)
    try:
        cookies.save(text)
    except cookies.CookieError as failure:
        return _error(str(failure), 400)
    except OSError:
        return _error("Couldn’t save the cookies on the server.", 500)
    return jsonify(cookies.info())


@bp.delete("/api/cookies")
def cookies_remove():
    refused = _local_only()
    if refused:
        return refused
    cookies.remove()
    return jsonify(cookies.info())
