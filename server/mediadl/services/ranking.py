CODEC_PRIORITY = {"h264": 0, "vp9": 1, "av1": 2, "h265": 3, "h266": 4}
UNKNOWN_CODEC_RANK = 99
ABOVE_TARGET_PENALTY = 10_000

QUALITY_TARGETS = {"best": None, "4k": 2160}


def quality_target(value):
    if value in QUALITY_TARGETS:
        return QUALITY_TARGETS[value]
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def quality_score(height, target):
    height = height or 0
    if target is None:
        return -height
    if height <= target:
        return target - height
    return ABOVE_TARGET_PENALTY + (height - target)


def rank_by_codec_and_quality(candidates, prefs):
    target = quality_target(((prefs or {}).get("video") or {}).get("quality", "best"))
    return sorted(
        candidates,
        key=lambda c: (
            CODEC_PRIORITY.get(c.get("codec"), UNKNOWN_CODEC_RANK),
            quality_score(c.get("height"), target),
        ),
    )
