import logging

from ..adapters import find_link, pick_adapter, platform_catalog
from ..services.selection import finalize
from .common import current_preferences

log = logging.getLogger(__name__)


def _unsupported_message():
    labels = [platform["label"] for platform in platform_catalog()]
    return "Paste a link from " + ", ".join(labels[:-1]) + ", or " + labels[-1] + "."


def run_parse(payload):
    link = find_link(payload.get("url", ""))
    adapter = pick_adapter(link)
    if not adapter:
        raise ParseFailure(_unsupported_message(), 400)

    try:
        post = adapter.extract(link)
    except ValueError as exc:
        raise ParseFailure(str(exc), 400)
    except Exception as exc:
        log.exception("extract failed for %s", adapter.name)
        raise ParseFailure(f"Could not parse {adapter.name} link: {exc.__class__.__name__}", 502)

    return finalize(post, adapter, current_preferences()), adapter, link


class ParseFailure(Exception):
    def __init__(self, message, status):
        super().__init__(message)
        self.message = message
        self.status = status
