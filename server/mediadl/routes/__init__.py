from . import appapi, cookies, jobs, media, pages, parse, settings, store, ytdlp


def register_routes(app):
    for module in (pages, settings, parse, media, store, appapi, ytdlp, jobs, cookies):
        app.register_blueprint(module.bp)
