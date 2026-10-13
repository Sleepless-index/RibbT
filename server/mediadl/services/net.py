import logging
import threading
from urllib.parse import urljoin, urlparse

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

from ..errors import MediaError

from ..config import (
    MAX_REDIRECTS,
    MEDIA_CONNECT_TIMEOUT_SECONDS,
    MEDIA_BASE_HEADERS,
    MEDIA_HOSTS,
    MEDIA_SOURCES,
    PAGE_HEADERS,
    PAGE_MAX_REDIRECTS,
    REDIRECT_CODES,
)

log = logging.getLogger(__name__)

_local = threading.local()

STREAM_TIMEOUT_SECONDS = 30


def get_session():
    session = getattr(_local, "session", None)
    if session is None:
        session = requests.Session()
        retry = Retry(
            total=2,
            connect=0,
            read=0,
            backoff_factor=0.3,
            status_forcelist=(500, 502, 503, 504),
            allowed_methods=frozenset({"GET", "POST", "HEAD"}),
        )
        adapter = HTTPAdapter(max_retries=retry)
        session.mount("https://", adapter)
        session.mount("http://", adapter)
        _local.session = session
    return session


def hostname(url):
    try:
        return (urlparse(url).hostname or "").lower()
    except ValueError:
        return ""


def host_in(host, allowed):
    return any(host == h or host.endswith("." + h) for h in allowed)


def host_ok(url, allowed):
    return host_in(hostname(url), allowed)


def fetch_page(url, allowed_hosts, headers=None):
    session = get_session()
    for _ in range(PAGE_MAX_REDIRECTS):
        if not host_ok(url, allowed_hosts):
            raise ValueError("Link redirected somewhere unexpected.")
        response = session.get(
            url, headers=headers or PAGE_HEADERS, timeout=15, allow_redirects=False
        )
        location = response.headers.get("Location")
        if response.status_code in REDIRECT_CODES and location:
            url = urljoin(url, location)
            continue
        return url, response.text
    raise ValueError("Too many redirects.")


DEFAULT_SOURCE = MEDIA_SOURCES[-1]


def header_sets(url, extra):
    host = hostname(url)
    source = next((s for s in MEDIA_SOURCES if host_in(host, s.hosts)), DEFAULT_SOURCE)
    sets = []
    for referer in source.referers:
        headers = dict(MEDIA_BASE_HEADERS)
        if referer:
            headers["Referer"] = referer
        sets.append({**headers, **extra})
    return sets


def _get(session, url, headers, timeout):
    return session.get(
        url,
        headers=headers,
        stream=True,
        timeout=(MEDIA_CONNECT_TIMEOUT_SECONDS, timeout),
        allow_redirects=False,
    )


def _request(session, url, extra, cache, timeout):
    host = hostname(url)
    known = cache.get(host)
    if known:
        return _get(session, url, {**known, **extra}, timeout)

    response = None
    for headers in header_sets(url, extra):
        if response is not None:
            response.close()
        response = _get(session, url, headers, timeout)
        if response.status_code not in (401, 403):
            cache[host] = {k: v for k, v in headers.items() if k not in extra}
            break
    return response


def open_media(url, extra_headers=None, cache=None, timeout=30):
    extra = extra_headers or {}
    cache = {} if cache is None else cache
    session = get_session()

    for _ in range(MAX_REDIRECTS):
        if not url.startswith("https://") or not host_ok(url, MEDIA_HOSTS):
            return None
        try:
            try:
                response = _request(session, url, extra, cache, timeout)
            except (requests.ConnectionError, requests.Timeout):
                if timeout < STREAM_TIMEOUT_SECONDS:
                    raise  # probes must fail fast
                # A stalled or reset streaming connection: drop pooled connections, try once more.
                _local.session = None
                session = get_session()
                response = _request(session, url, extra, cache, timeout)
        except requests.RequestException as exc:
            log.warning("media request failed for %.110s: %s", url, exc)
            raise MediaError(
                f"Could not reach the media server ({exc.__class__.__name__}). Try again.", 502
            ) from exc

        location = response.headers.get("Location")
        if response.status_code in REDIRECT_CODES and location:
            url = urljoin(url, location)
            response.close()
            continue
        if response.status_code >= 400:
            log.info("media %s %.110s", response.status_code, url)
        response.final_url = url
        return response
    return None


def resolve_media(url):
    cache = {}
    response = open_media(url, {"Range": "bytes=0-0"}, cache, timeout=10)
    if response is None:
        return None, None
    final_url = getattr(response, "final_url", url)
    status = response.status_code
    response.close()
    if status >= 400:
        return None, None
    return final_url, cache.get(hostname(final_url)) or header_sets(final_url, {})[0]


def probe(url, kind, cache):
    try:
        response = open_media(url, {"Range": "bytes=0-0"}, cache, timeout=8)
    except MediaError:
        return False
    if response is None:
        return False
    content_type = response.headers.get("Content-Type", "")
    ok = response.status_code in (200, 206) and (
        content_type.startswith(kind) or "octet-stream" in content_type
    )
    response.close()
    return ok


def content_type_of(url, timeout=8):
    try:
        response = open_media(url, {"Range": "bytes=0-0"}, {}, timeout=timeout)
    except MediaError:
        return None
    if response is None:
        return None
    content_type = response.headers.get("Content-Type", "").split(";")[0].strip()
    response.close()
    return content_type
