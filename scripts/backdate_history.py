#!/usr/bin/env python3
"""Create OneBattery's backdated git history from config/devlog-releases.json.

Dry run by default (prints the plan). Pass --apply to create commits and tags. It never pushes.

Honest-history approach: the source for the early releases was never kept (only their APKs),
so this does not invent old source trees. For each backfilled release (oldest first) it makes
one commit, dated at that release's original build time, that adds only
docs/history/<version>.md (the release notes, plus a note that the source was not kept).
Each of these commits gets an annotated tag v<version>. The last commit adds the whole current
tree at the version in version.properties, dated now, and gets its tag too.

Every commit carries the Bradshaw/DevLog trailers (DevLog-Release, DevLog-Note, DevLog-Compat,
DevLog-Action). On the first production push, `scripts/devlog-release.mjs prepare --production`
reads the DevLog-Release trailers and writes each commit's SHA into the registry.

Refuses to run unless the repository exists, has no commits, has nothing staged, and is on main.
Phoenix is UTC-7 all year (no DST), so the offset is fixed at -07:00 (no tzdata needed on Windows).
"""
from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REGISTRY = ROOT / "config" / "devlog-releases.json"
VERSION_FILE = ROOT / "version.properties"
HISTORY_DIR = ROOT / "docs" / "history"
PHOENIX = timezone(timedelta(hours=-7), "America/Phoenix")

# Same identity the aribradshaw/devlog repository's own commits use (links commits to the profile).
AUTHOR_NAME = "Ari Bradshaw"
AUTHOR_EMAIL = "46178296+aribradshaw@users.noreply.github.com"

# Never let these into the final snapshot commit, even if .gitignore changes.
FORBIDDEN = [
    "keystore.properties", "signing.properties", "local.properties", "*.jks", "*.keystore", "*.p12",
    "*.pk8", "*.pem", "*.der", "*.apk", "*.aab", "google-services.json", "*service-account*.json",
]


def git(*args: str, env: dict | None = None, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=ROOT, env=env, check=check, text=True, encoding="utf-8", capture_output=True)


def fail(message: str) -> None:
    print(f"backdate_history: {message}", file=sys.stderr)
    sys.exit(1)


def version_key(v: str) -> tuple[int, ...]:
    return tuple(int(p) for p in v.split("."))


def phoenix(iso: str) -> datetime:
    return datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone(PHOENIX)


def message_for(entry: dict, backfilled: bool) -> str:
    lines = [f"Release {entry['version']}: {entry['title']}", ""]
    if entry.get("summary"):
        lines += [entry["summary"], ""]
    legacy = entry.get("legacyVersion")
    if backfilled:
        lines += [
            f"Backdated record of the release first shipped as v{legacy} on {entry['date']}.",
            "Its source was not kept; this commit adds only the release notes.",
            "",
        ]
    elif legacy:
        lines += [f"Previously planned as v{legacy}. Full source tree at this release.", ""]
    trailers = [f"DevLog-Release: {entry['version']}"]
    trailers += [f"DevLog-Note: {n}" for n in entry["notes"]]
    trailers.append(f"DevLog-Compat: {entry.get('compatibility') or 'compatible'}")
    if entry.get("action"):
        trailers.append(f"DevLog-Action: {entry['action']}")
    return "\n".join(lines + trailers) + "\n"


def history_doc(entry: dict) -> str:
    out = [f"# OneBattery {entry['version']} ({entry['date']})", ""]
    if entry.get("legacyVersion"):
        out += [f"First released as **v{entry['legacyVersion']}**; renumbered under Bradshaw Versioning on 2026-11-11.", ""]
    out += [f"## {entry['title']}", ""]
    if entry.get("summary"):
        out += [entry["summary"], ""]
    out += [f"- {n}" for n in entry["notes"]]
    if entry.get("action"):
        out += ["", f"**Action required:** {entry['action']}"]
    out += [
        "",
        "The source for this release was not kept, so this commit holds only these notes.",
        "The APK, where it still exists, is attached to the GitHub Release for this tag.",
        "",
    ]
    return "\n".join(out)


def safety_checks(plan: list) -> None:
    if git("rev-parse", "--is-inside-work-tree", check=False).stdout.strip() != "true":
        fail(f"{ROOT} is not a git work tree; run `git init -b main` first")
    top = git("rev-parse", "--show-toplevel").stdout.strip()
    if Path(top).resolve() != ROOT:
        fail(f"the git top level ({top}) is not the project root")
    if git("rev-parse", "--verify", "-q", "HEAD", check=False).returncode == 0:
        fail("the repository already has commits; refusing to rewrite or add backdated history")
    branch = git("symbolic-ref", "--short", "HEAD").stdout.strip()
    if branch != "main":
        fail(f"current branch is {branch}, expected main")
    if git("diff", "--cached", "--name-only", check=False).stdout.strip():
        fail("something is already staged; unstage it first (git rm -r --cached .)")
    existing_tags = git("tag", "--list").stdout.split()
    clash = [f"v{e['version']}" for e, _, _ in plan if f"v{e['version']}" in existing_tags]
    if clash:
        fail(f"tags already exist: {', '.join(clash)}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apply", action="store_true", help="create the commits and tags (default: dry run)")
    args = parser.parse_args()

    entries = json.loads(REGISTRY.read_text(encoding="utf-8"))
    current_version = re.search(r"^version\s*=\s*(\S+)", VERSION_FILE.read_text(encoding="utf-8"), re.M).group(1)
    newest = max(entries, key=lambda e: version_key(e["version"]))
    if newest["version"] != current_version:
        fail(f"version.properties is {current_version} but the newest release is {newest['version']}")
    if any(e.get("commit") for e in entries):
        fail("registry entries already have commit SHAs; history was already created")
    history = sorted((e for e in entries if e.get("backfilled")), key=lambda e: version_key(e["version"]))
    for e in history:
        if not e.get("releasedAt"):
            fail(f"backfilled release {e['version']} has no releasedAt")
    if [e for e in entries if not e.get("backfilled") and e is not newest]:
        fail("only the newest release may be non-backfilled")

    plan = [(e, phoenix(e["releasedAt"]), True) for e in history]
    plan.append((newest, None, False))
    # Dates must strictly increase (git log order and Bradshaw chronology).
    dates = [d for _, d, _ in plan if d]
    if dates != sorted(dates) or len(set(dates)) != len(dates):
        fail("backfilled release dates are not strictly increasing")

    if args.apply:
        safety_checks(plan)

    print("Planned history (oldest first), author/committer "
          f"{AUTHOR_NAME} <{AUTHOR_EMAIL}>:\n")
    for i, (e, when, backfilled) in enumerate(plan, 1):
        stamp = when.isoformat() if when else "now (time of --apply), current tree"
        files = f"docs/history/{e['version']}.md" if backfilled else "entire working tree (git add -A)"
        legacy = f"(was v{e['legacyVersion']})" if e.get("legacyVersion") else ""
        print(f"{i}. {stamp:<27} tag v{e['version']:<8} {legacy:<11} {e['title']}")
        print(f"   adds: {files}; compat: {e.get('compatibility') or 'compatible'}; notes: {len(e['notes'])}")
    if not args.apply:
        print("\nDry run only. Re-run with --apply to create these commits and tags (nothing is pushed).")
        return

    base_env = {**os.environ,
                "GIT_AUTHOR_NAME": AUTHOR_NAME, "GIT_AUTHOR_EMAIL": AUTHOR_EMAIL,
                "GIT_COMMITTER_NAME": AUTHOR_NAME, "GIT_COMMITTER_EMAIL": AUTHOR_EMAIL}

    for e, when, backfilled in plan:
        env = dict(base_env)
        if when:
            stamp = when.isoformat()
            env["GIT_AUTHOR_DATE"] = stamp
            env["GIT_COMMITTER_DATE"] = stamp
        if backfilled:
            HISTORY_DIR.mkdir(parents=True, exist_ok=True)
            doc = HISTORY_DIR / f"{e['version']}.md"
            doc.write_text(history_doc(e), encoding="utf-8", newline="\n")
            git("add", "--", str(doc.relative_to(ROOT)).replace("\\", "/"))
        else:
            git("add", "-A")
            staged = git("diff", "--cached", "--name-only").stdout.split()
            bad = [p for p in staged if any(fnmatch.fnmatch(Path(p).name, pat) for pat in FORBIDDEN)]
            if bad:
                git("reset", "-q", check=False)
                fail("refusing to commit sensitive or built files (fix .gitignore): " + ", ".join(bad))
        subprocess.run(["git", "commit", "-q", "--no-verify", "--cleanup=strip", "-F", "-"], cwd=ROOT, env=env,
                       check=True, text=True, encoding="utf-8", input=message_for(e, backfilled))
        legacy = f" (was v{e['legacyVersion']})" if e.get("legacyVersion") else ""
        git("tag", "-a", f"v{e['version']}", "-m", f"OneBattery {e['version']}{legacy}: {e['title']}", env=env)
        sha = git("rev-parse", "HEAD").stdout.strip()
        print(f"created {sha[:10]} v{e['version']}")

    print("\nDone. Review with `git log --format='%h %ad %s' --date=iso` and `git tag -n1`.")
    print("Nothing was pushed. When ready: git push -u origin main --follow-tags")


if __name__ == "__main__":
    main()
