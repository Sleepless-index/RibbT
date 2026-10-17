import logging
import os

from flask import Flask, Response, request

from .errors import MediaError
from .services import jobs
from .routes import register_routes


def _handle_media_error(error):
    return Response(error.message, status=error.status, mimetype="text/plain")


_LOCAL_HOSTS = {"127.0.0.1", "localhost", "[::1]"}


def _lock_to_app(app):
    @app.before_request
    def _app_only():
        headers = request.headers
        host = (headers.get("Host") or "").rsplit(":", 1)[0] if not (headers.get("Host") or "").endswith("]") else headers.get("Host")
        browser = any(name in headers for name in ("Origin", "Sec-Fetch-Site", "Sec-Fetch-Mode", "Sec-Fetch-Dest"))
        if browser or host not in _LOCAL_HOSTS:
            return Response("RibbT's downloader runs inside the app.", status=404, mimetype="text/plain")
        return None


def create_app():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    app = Flask(__name__, static_folder=None)
    app.register_error_handler(MediaError, _handle_media_error)
    register_routes(app)
    if os.environ.get("RIBBT_EMBEDDED") == "1":
        _lock_to_app(app)
    jobs.init(app)
    return app
