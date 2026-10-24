from .dapi import DapiAdapter


class GelbooruAdapter(DapiAdapter):
    name = "gelbooru"
    label = "Gelbooru"
    features = ("Photos", "Video")
    hosts = ("gelbooru.com",)
    site = "https://gelbooru.com"
