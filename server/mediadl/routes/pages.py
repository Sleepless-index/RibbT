from flask import Blueprint, render_template

from ..adapters import platform_catalog
from .common import current_preferences

bp = Blueprint("pages", __name__)


@bp.get("/")
def index():
    layout = current_preferences()["display"]["layout"]
    return render_template("index.html", platforms=platform_catalog(), layout=layout)
