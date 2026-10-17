from . import appapi, cookies, jobs, media, settings, ytdlp


def register_routes(app):
    for module in (settings, media, appapi, ytdlp, jobs, cookies):
        app.register_blueprint(module.bp)
