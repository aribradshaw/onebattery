# Versioning and the devlog

OneBattery uses **[Bradshaw Versioning 1.0](https://github.com/aribradshaw/devlog/blob/main/docs/bradshaw-versioning.md)**
(Ari Bradshaw, active since 2026-11-02) and the release model of
**[@aribradshaw/devlog](https://github.com/aribradshaw/devlog)**.

## The scheme

`YEAR.MONTH.UPDATE`, three plain integers with no leading zeros. Compare them numerically, never as text.

| Field | OneBattery's declared setting |
| --- | --- |
| Year | One-based project year, origin **2026**: 2026 = `1`, 2027 = `2`, 2028 = `3`. It advances each January |
| Month, project year 1 (2026) | **Project-relative** from the origin month, **August 2026** = `0`: Sep = `1`, Oct = `2`, Nov = `3`, Dec = `4` |
| Month, project year 2 onward | **Calendar months**: Jan = `1` … Dec = `12` |
| Update | Starts at `1`, goes up by one per production release, and resets to `1` on any year or month change |
| Time zone | **America/Phoenix** (UTC-7 all year) for month and year boundaries |
| Baseline | `1.2.1` = the first build (2026-10-30). Cut over to Bradshaw Versioning on 2026-11-11 at `1.3.6` |
| Registry | `config/devlog-releases.json` (a `DevLogEntry[]` array, newest first) |
| Single source for the build | `version.properties` → `versionName` = `1.3.6`, `versionCode` = `Y*1_000_000 + M*10_000 + U` = `1030006` |

This is the owner's rule (2026-11-11): *"November should be 1.3.x and then in Jan it'll loop back to 2.1.x."*

| Period (Phoenix) | Versions |
| --- | --- |
| Aug 2026 | `1.0.1`, `1.0.2`, … |
| Oct 2026 | `1.2.x` |
| Nov 2026 | `1.3.x` (now `1.3.6`) |
| Dec 2026 | `1.4.1` … |
| Jan 2027 | `2.1.1` … |
| Dec 2027 | `2.12.1` … |
| Jan 2028 | `3.1.1` … |

`node scripts/devlog-release.mjs selftest` checks these boundaries, including the Phoenix midnights, skipped months
and refusing to go backwards. It runs automatically before every `prepare` and `check`.

**versionCode** = `YEAR*1_000_000 + MONTH*10_000 + UPDATE`. Because MONTH ≤ 12 and UPDATE ≤ 9,999,
`MONTH*10_000 + UPDATE` stays below 1,000,000, so the code always increases. That holds when the month drops from
`4` (Dec 2026, `1.4.x` → 104xxxx) to `1` (Jan 2027, `2.1.1` → 2010001). It holds until year 2100, Play's
2,100,000,000 limit.

These numbers are calendar identities, not SemVer. A change in UPDATE does not promise backward compatibility. Each
release instead declares `compatibility` as `compatible`, `action-required` or `breaking` (the highest across its
commits wins), plus an `action` when the user must do something.

Do not change the year mode, origin, month rule or time zone without recording a revision here.

## What triggers a release

Every push to `main` triggers one (`.github/workflows/release.yml`):

1. The run syncs to the tip of `main` (failing if the pushed commit is no longer on it), so the version is
   computed against the newest registry, including releases recorded by earlier queued runs.
2. `node scripts/devlog-release.mjs prepare --production` assigns the next version to the pushed commit. It is
   idempotent per commit SHA, so a re-run reuses the same version. Its time is the push admission time
   (`github.event.repository.pushed_at`), not the author date. It writes the new `DevLogEntry` to the registry
   and the version to `version.properties`. Every later step is gated on its status: only `created`, `reused`
   or `linked` continue. A `skipped` run (`DevLog:` / `chore(release):` commits) builds and uploads nothing.
3. `check --production` fails the run if the registry, `version.properties` and the commit disagree (fail closed).
4. A bot commit `chore(release): publish DevLog <version>` stores the metadata. The recorder skips that prefix,
   so it never loops. The workflow tags `v<version>` on the source commit and pushes the branch and the tag in
   one `git push --atomic`, so a rejected branch push never leaves a published tag behind. If `main` moved, it
   re-syncs, recomputes the version and retries (at most 5 attempts). A tag that already exists on a different
   commit fails the run.
5. Gradle builds the APK from the tagged source commit plus the final registry and version. `versionName`/
   `versionCode` come from `version.properties`, and the registry is bundled as `assets/devlog.json` (task
   `devlogAssets`, wired via `androidComponents`). The in-app devlog reads that.
6. The GitHub Release is created (or, on a re-run, its asset replaced) only after checking that the remote tag
   points at the source commit.

Runs are serialized (`concurrency: bradshaw-release`).

### Writing the copy: commit trailers

The title is the head commit's subject, with any `feat:`/`fix(ui):` prefix removed. Notes default to each commit's
subject. Trailers override them:

```text
feat: home-screen widget

DevLog-Note: A home-screen widget shows the battery level.
DevLog-Note: Tap it to open OneBattery.
DevLog-Compat: action-required
DevLog-Action: Add the widget from your home screen.
```

Commits whose subject starts with `chore(release):` or `DevLog:` are left out of the notes.
`DevLog-Release: <version>` binds a commit to a release that already exists in the registry. The backdated
history uses it.

### Local commands

```sh
node scripts/devlog-release.mjs next            # version a release would get right now
node scripts/devlog-release.mjs selftest        # month rule + versionCode checks
node scripts/devlog-release.mjs notes 1.3.6     # Markdown release body
node scripts/devlog-release.mjs link            # bind DevLog-Release trailers to registry entries
node scripts/devlog-release.mjs prepare --production --dry-run
```

`scripts/devlog-release.mjs` has no dependencies (Node 20+). The version math lives in the script itself because
neither of the package's helpers implements OneBattery's month rule. `nextBradshawVersion` (DevLog 1.3.1) uses
calendar months throughout, which would make November 2026 `1.11.x`. `nextCalendarVersion` resets January to month
`0`, which would make January 2027 `2.0.1`. The script copies the package's `nextBradshawVersion` guard: it refuses
any version that doesn't sort after the latest one.

## Conformance and disclosed deviations

These deviations from the specification are deliberate:

- **Month rule (spec §1).** The spec's project-relative mode counts from the origin month and resets to `0` each
  January, so January 2027 would be `2.0.x`. OneBattery is project-relative only during project year 1
  (Aug 2026 = `0` … Dec 2026 = `4`) and uses calendar months from January 2027 (`2.1.x`), by owner decision.
  The spec has no name for this mixed mode, so record it as a OneBattery-specific declaration.
- **One version per push, not per commit (spec §2).** OneBattery follows the owner decision recorded for DevLog
  1.3.1's deploy-time recorder: each push to `main` gets one release, and every new commit is listed in
  `includedCommits`.
- **Generated metadata commits are not numbered (spec §2).** The bot's `chore(release):` commits stay unnumbered
  to avoid a loop.
- **Registry durability (spec §3).** The registry is a git-tracked JSON file. Assignment is serialized by the
  workflow's concurrency group and by fast-forward-only pushes, not by a database constraint.
- **Deployment evidence.** A GitHub Release is the "deployment". Installing on a phone is not recorded.
- **Backfilled versions (spec §5 item 4).** At the owner's request, the releases before the cutover were given
  Bradshaw numbers after the fact, on 2026-11-11. Their original identifiers are kept in `legacyVersion` and
  shown in the app as "was v1.x", so those versions keep both identities.

## Backdated mapping

The dates are the original build times in America/Phoenix, taken from the APK file times in `dist/` and the
build transcripts. v1.3 has no surviving APK, so its time comes from the source file times
(`MonitorSettings.kt`). The commit numbering is 1:1 because each backdated release is one commit.

| Bradshaw | Was | Built (Phoenix) | Title | Compat | Evidence |
| --- | --- | --- | --- | --- | --- |
| `1.2.1` | v1.0 | 2026-10-30 19:00 | OneBattery by Flygon LC: live stats, health estimate, history | compatible | `dist/OneBattery-v1.0.apk`, transcript 2026-10-31T01:58Z |
| `1.2.2` | v1.1 | 2026-10-30 19:18 | Real capacity measurement (coulomb counting, background monitor) | action-required | `dist/OneBattery-v1.1.apk`, transcript 02:18Z |
| `1.3.1` | v1.2 | 2026-11-05 20:07 | What's draining your battery | action-required | `dist/OneBattery-v1.2.apk`, 2026-11-06 session transcript |
| `1.3.2` | v1.3 | 2026-11-08 06:18 | Settings and a quieter notification | compatible | source mtimes only (no APK, no transcript) |
| `1.3.3` | v1.4 | 2026-11-10 19:22 | Theme picker | compatible | `dist/OneBattery-v1.4.apk` |
| `1.3.4` | v1.5 | 2026-11-10 19:25 | Four themes, not five | compatible | `dist/OneBattery-v1.5.apk` |
| `1.3.5` | v1.6 | 2026-11-10 19:46 | The four Flygon LC house themes | compatible | `dist/OneBattery-v1.6.apk` |
| `1.3.6` | v1.7 | 2026-11-11 (cutover) | Themed icons, motion and a devlog | compatible | current tree |

The old `versionCode`s were 1–7 (v1.7 was planned as 8). `1030006` is larger than all of them, so `1.3.6` installs
as an update over any earlier sideloaded build.

### Creating the git history

`scripts/backdate_history.py` runs as a dry run by default. Pass `--apply` to execute it. It refuses if the repo
already has commits. No source snapshots survive for 1.2.1–1.3.5, so each of those becomes one commit, dated at
its build time, that adds only `docs/history/<version>.md` (its release notes, stating that the source was not
kept). Each gets an annotated tag `v<version>` (`v1.2.1` … `v1.3.5`). The last commit holds the full current
tree at `1.3.6` (tag `v1.3.6`), dated
at run time. All commits carry `DevLog-Release`/`DevLog-Note`/`DevLog-Compat`/`DevLog-Action` trailers. The first
CI run binds their SHAs into the registry.

Optionally, attach the surviving APKs to their tags after pushing:

```sh
gh release create v1.2.1 dist/OneBattery-v1.0.apk --title "OneBattery 1.2.1" --notes "$(node scripts/devlog-release.mjs notes 1.2.1)"
# …likewise v1.2.2 (v1.1), v1.3.1 (v1.2), v1.3.3 (v1.4), v1.3.4 (v1.5), v1.3.5 (v1.6). v1.3.2 (v1.3) has no APK.
```
