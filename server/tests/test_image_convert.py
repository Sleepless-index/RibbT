import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from mediadl.services import net  # noqa: E402

JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 28


class FakeResponse:
    def __init__(self, content_type, body):
        self.headers = {"Content-Type": content_type}
        self._body = body

    def iter_content(self, size):
        yield self._body[:size]

    def close(self):
        pass


class ImageTypeTests(unittest.TestCase):
    def kind(self, url, content_type, body):
        with mock.patch.object(net, "open_media", return_value=FakeResponse(content_type, body)):
            return net.image_type_of(url)

    def test_bytes_win_over_a_useless_header(self):
        self.assertEqual(self.kind("https://x/a", "application/octet-stream", JPEG), "image/jpeg")

    def test_header_used_when_bytes_are_unknown(self):
        self.assertEqual(self.kind("https://x/a", "image/avif", b"????"), "image/avif")

    def test_extension_is_the_last_resort(self):
        self.assertEqual(self.kind("https://x/a.png", "binary/octet-stream", b""), "image/png")

    def test_unreachable_server_gives_nothing(self):
        with mock.patch.object(net, "open_media", return_value=None):
            self.assertIsNone(net.image_type_of("https://x/a"))


if __name__ == "__main__":
    unittest.main()
