from ..services.net import fetch_page, host_ok
from ..services.ranking import rank_by_codec_and_quality


class PlatformAdapter:
    name = ""
    label = ""
    hosts = ()
    display_hosts = ()
    subtitles = False

    def matches(self, url):
        return host_ok(url, self.hosts)

    def extract(self, url):
        raise NotImplementedError

    def rank(self, candidates, prefs):
        return rank_by_codec_and_quality(candidates, prefs)

    def is_playlist(self, url):
        return False

    def has_playlist(self, url):
        return False

    def extract_playlist(self, url):
        raise ValueError("Playlists aren’t supported for this site.")

    def fetch_page(self, url, headers=None):
        return fetch_page(url, self.hosts, headers)


def new_post(*, id="", title="", author="", avatar="", kind="images"):
    return {
        "id": id,
        "title": title,
        "author": author,
        "avatar": avatar,
        "kind": kind,
        "_cover": None,
        "_videos": [],
        "_images": [],
        "subtitles": [],
    }


def video_group(candidates, label="Video", preview=None, **extras):
    return {"label": label, "cands": candidates, "preview": preview, **extras}
