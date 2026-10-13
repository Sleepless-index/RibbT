class MediaError(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.message = message
        self.status = status


def blocked_media_url():
    return MediaError("Blocked or unreachable media URL", 400)
