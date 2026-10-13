import logging

from flask import Flask, Response

from .errors import MediaError
from .routes import register_routes


def _handle_media_error(error):
    return Response(error.message, status=error.status, mimetype="text/plain")


def create_app():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    app = Flask(__name__)
    app.register_error_handler(MediaError, _handle_media_error)
    register_routes(app)
    return app
