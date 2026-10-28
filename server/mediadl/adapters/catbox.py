from urllib.parse import unquote, urlparse

from ..services.candidates import candidate, clean_url
from .base import PlatformAdapter, new_post, video_group

TITLE_LIMIT = 80
IMAGE_EXTENSIONS = ("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp")
VIDEO_EXTENSIONS = ("mp4", "webm", "mov", "m4v", "mkv")
AUDIO_EXTENSIONS = ("mp3", "wav", "ogg", "oga", "opus", "flac", "m4a", "aac")


class CatboxAdapter(PlatformAdapter):
    """Catbox and Litterbox host plain files, so a link to one is the file itself."""

    name = "catbox"
    label = "Catbox"
    features = ("Photos", "Video", "Audio")
    hosts = ("catbox.moe",)
    note = "Paste the direct file link (files.catbox.moe/…)."

    def extract(self, url):
        path = unquote(urlparse(url).path).rstrip("/")
        filename = path.rsplit("/", 1)[-1]
        stem, _, extension = filename.rpartition(".")
        extension = extension.lower()
        if not stem or extension not in IMAGE_EXTENSIONS + VIDEO_EXTENSIONS + AUDIO_EXTENSIONS:
            raise ValueError("Paste the direct link to a Catbox file, like files.catbox.moe/abc123.jpg.")
        file_url = clean_url(url)

        if extension in VIDEO_EXTENSIONS:
            post = new_post(id=stem, title=filename[:TITLE_LIMIT], kind="video")
            post["_videos"] = [video_group([candidate(file_url)])]
        elif extension in AUDIO_EXTENSIONS:
            post = new_post(id=stem, title=stem[:TITLE_LIMIT], kind="audio")
            post["audio_track"] = {"url": file_url, "hls": False, "size": None}
        else:
            post = new_post(id=stem, title=filename[:TITLE_LIMIT], kind="images")
            post["_images"] = [[file_url]]
            post["_cover"] = [file_url]
        return post
