"""Sign-in cookies for yt-dlp (YouTube's age and "confirm you're not a bot" checks).

The user exports a ``cookies.txt`` from their browser and imports it in the app. Only cookies for the
sites RibbT supports are kept (plus Google, which YouTube signs in through), so a browser-wide export
doesn't leave unrelated logins lying around. The file is private to the server's data folder and its
contents are never sent back to the app, only a summary of what is in it.
"""
import os
import time
from pathlib import Path

from ..config import PROJECT_ROOT

HEADER = "# Netscape HTTP Cookie File"
MAX_BYTES = 2 * 1024 * 1024
LEGACY_ENV = "YTDLP_COOKIES"
HTTP_ONLY = "#HttpOnly_"
# Google signs YouTube in through google.com, so those cookies are needed too.
EXTRA_DOMAINS = ("google.com", "youtube.com")


class CookieError(ValueError):
    pass


def store_path():
    configured = os.environ.get("RIBBT_COOKIES_PATH")
    return Path(configured) if configured else PROJECT_ROOT / "config" / "cookies.txt"


def active_path():
    """The cookie file for yt-dlp, or None. An imported file beats the YTDLP_COOKIES setting."""
    imported = store_path()
    if imported.is_file():
        return str(imported)
    legacy = os.environ.get(LEGACY_ENV)
    return legacy if legacy and os.path.isfile(legacy) else None


def _allowed_domains():
    from ..adapters import platform_catalog

    hosts = {host.lower() for platform in platform_catalog() for host in platform["hosts"]}
    return tuple(hosts | set(EXTRA_DOMAINS))


def _domain_ok(domain, allowed):
    domain = domain.lower().lstrip(".")
    return any(domain == host or domain.endswith("." + host) for host in allowed)


def clean(text):
    """Parse a cookies.txt. Returns (text to store, summary). Raises CookieError when unusable."""
    allowed = _allowed_domains()
    kept, domains, dropped = [], set(), 0
    for raw in (text or "").replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        line = raw.strip("\n")
        if not line.strip():
            continue
        if line.startswith("#") and not line.startswith(HTTP_ONLY):
            continue
        fields = line.split("\t")
        if len(fields) == 6:
            fields.append("")  # a cookie with an empty value may lose its trailing tab
        if len(fields) != 7:
            continue
        domain = fields[0][len(HTTP_ONLY):] if fields[0].startswith(HTTP_ONLY) else fields[0]
        if not domain or fields[1] not in ("TRUE", "FALSE") or fields[3] not in ("TRUE", "FALSE"):
            continue
        if _domain_ok(domain, allowed):
            kept.append("\t".join(fields))
            domains.add(domain.lower().lstrip("."))
        else:
            dropped += 1
    if not kept and not dropped:
        raise CookieError(
            "That doesn’t look like a cookies.txt file. Export it from your browser in the Netscape format."
        )
    if not kept:
        raise CookieError("That file has no cookies for the sites RibbT supports, so nothing was imported.")
    return HEADER + "\n" + "\n".join(kept) + "\n", {"count": len(kept), "dropped": dropped, "domains": sorted(domains)}


def save(text):
    if len(text.encode("utf-8", "ignore")) > MAX_BYTES:
        raise CookieError("That file is too big to be a cookies.txt.")
    stored, summary = clean(text)
    path = store_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".tmp")
    # Created private from the start: the file holds live sign-in sessions.
    descriptor = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(stored)
    os.replace(temp, path)
    return summary


def remove():
    try:
        store_path().unlink()
        return True
    except FileNotFoundError:
        return False


def info():
    """What is stored, without any cookie values."""
    path = store_path()
    if not path.is_file():
        return {"present": False, "count": 0, "domains": [], "saved_at": None}
    try:
        _, summary = clean(path.read_text(encoding="utf-8"))
        saved_at = int(path.stat().st_mtime)
    except (OSError, CookieError):
        return {"present": False, "count": 0, "domains": [], "saved_at": None}
    return {"present": True, "count": summary["count"], "domains": summary["domains"], "saved_at": saved_at}
