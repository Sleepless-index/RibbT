import re
import subprocess
import sys
from datetime import date

SECTIONS = [
    ("Added", ("add", "adds", "added", "introduce", "support", "implement")),
    ("Changed", ("change", "update", "improve", "animate", "rework", "refactor", "use", "move", "rename", "make", "show", "simplify")),
    ("Fixed", ("fix", "fixes", "fixed", "repair", "resolve", "correct", "handle")),
    ("Removed", ("remove", "removes", "removed", "drop", "delete")),
]
SKIP = re.compile(r"^(merge|revert \"merge|wip\b)", re.IGNORECASE)
FALLBACK_COUNT = 15


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def subjects(base, head):
    if base and subprocess.run(["git", "cat-file", "-e", base + "^{commit}"], capture_output=True).returncode == 0:
        span = f"{base}..{head}"
        out = git("log", "--no-merges", "--format=%s", span)
    else:
        out = git("log", "--no-merges", "--format=%s", f"-{FALLBACK_COUNT}", head)
    return [line.strip() for line in out.splitlines() if line.strip() and not SKIP.match(line.strip())]


def section_for(subject):
    word = re.split(r"[\s:]", subject, maxsplit=1)[0].lower()
    for title, verbs in SECTIONS:
        if word in verbs:
            return title
    return "Other"


def render(version, base, head, repo):
    groups = {}
    for subject in subjects(base, head):
        groups.setdefault(section_for(subject), []).append(subject[0].upper() + subject[1:])
    lines = [f"## RibbT {version} · {date.today().isoformat()}", ""]
    order = [title for title, _ in SECTIONS] + ["Other"]
    for title in order:
        if title in groups:
            lines.append(f"### {title}")
            lines += [f"- {item}" for item in dict.fromkeys(groups[title])]
            lines.append("")
    if not groups:
        lines += ["No notable changes.", ""]
    if base and repo:
        lines.append(f"Full diff: https://github.com/{repo}/compare/{base[:7]}...{head[:7]}")
    return "\n".join(lines).rstrip() + "\n"


def main():
    version = sys.argv[1]
    base = sys.argv[2] if len(sys.argv) > 2 else ""
    head = sys.argv[3] if len(sys.argv) > 3 else "HEAD"
    repo = sys.argv[4] if len(sys.argv) > 4 else ""
    head_sha = git("rev-parse", head).strip()
    sys.stdout.write(render(version, base, head_sha, repo))


main()
