import json
import threading
import time
import uuid

from .. import config

_lock = threading.Lock()
FIELDS = ("link", "title", "author", "platform", "kind", "thumb", "saved_to")
SIZE_KINDS = ("video", "image", "audio")
MAX_BYTES = 1 << 40


def _read():
    try:
        with open(config.HISTORY_PATH, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return []
    return data if isinstance(data, list) else []


def _write(items):
    config.HISTORY_PATH.parent.mkdir(parents=True, exist_ok=True)
    temp = config.HISTORY_PATH.with_suffix(".tmp")
    with open(temp, "w", encoding="utf-8") as handle:
        json.dump(items, handle, ensure_ascii=False)
    temp.replace(config.HISTORY_PATH)


def _clean_sizes(raw):
    """Bytes saved per kind, so the app can show what the downloads take up."""
    raw = raw if isinstance(raw, dict) else {}
    sizes = {}
    for kind in SIZE_KINDS:
        try:
            sizes[kind] = max(0, min(int(raw.get(kind) or 0), MAX_BYTES))
        except (TypeError, ValueError):
            sizes[kind] = 0
    return sizes


def list_items():
    with _lock:
        return _read()


def add_item(entry):
    clean = {key: str(entry.get(key) or "")[:500] for key in FIELDS}
    clean["files"] = max(1, min(int(entry.get("files") or 1), 999)) if str(entry.get("files") or "1").isdigit() else 1
    clean["sizes"] = _clean_sizes(entry.get("sizes"))
    clean["time"] = int(time.time())
    with _lock:
        items = [item for item in _read() if item.get("link") != clean["link"]]
        clean["id"] = uuid.uuid4().hex[:12]
        items.insert(0, clean)
        _write(items[: config.HISTORY_LIMIT])
    return clean


def remove_item(item_id):
    with _lock:
        items = _read()
        kept = [item for item in items if item.get("id") != item_id]
        if len(kept) == len(items):
            return False
        _write(kept)
        return True


def clear_items():
    with _lock:
        _write([])
