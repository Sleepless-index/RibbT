from .dapi import DapiAdapter


class SafebooruAdapter(DapiAdapter):
    name = "safebooru"
    label = "Safebooru"
    features = ("Photos",)
    hosts = ("safebooru.org",)
    site = "https://safebooru.org"
