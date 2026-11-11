#!/usr/bin/env node
// OneBattery release recorder: Bradshaw Versioning + DevLog, file registry (no server, no database).
//
// Follows @aribradshaw/devlog (https://github.com/aribradshaw/devlog): the registry is a DevLogEntry[]
// JSON file at the package's default path (config/devlog-releases.json), versions come from
// OneBattery's Bradshaw scheme (below), and commit trailers shape the copy (DevLog-Note / DevLog-Compat /
// DevLog-Action).
// Like the package's deploy-time recorder, one push to main = one release that lists every new
// commit in includedCommits (a disclosed deviation from spec section 2; see docs/VERSIONING.md).
//
// Zero dependencies (Node 20+). The version math is local because OneBattery's month rule
// (project-relative in year 1, calendar months afterwards) matches neither of the package's helpers.
//
// Commands:
//   selftest                  Check the month rule (Aug 2026 = 1.0.x … Jan 2027 = 2.1.1) and versionCode.
//   next                      Print the version the next release would get right now.
//   prepare [--production]    Record the release for HEAD (idempotent) and write version.properties.
//   check [--production]      Fail when registry, version.properties and HEAD disagree.
//   link                      Bind registry entries with commit null to commits carrying a
//                             "DevLog-Release: <version>" trailer (used for the backdated history).
//   notes <version>           Print a Markdown release body (for GitHub Releases).
//
// Env (all optional): DEVLOG_PRODUCTION, DEVLOG_BRANCH, DEVLOG_COMMIT_SHA, DEVLOG_RELEASED_AT
// (ISO or epoch seconds: the push admission time), GITHUB_REF_NAME, GITHUB_SHA, GITHUB_OUTPUT.

import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, renameSync, writeFileSync, appendFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const VERSION_RE = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/
const SHA_RE = /^[a-f0-9]{40}$/
const COMPAT_RANK = { compatible: 0, 'action-required': 1, breaking: 2 }
const MAX_COMMITS = 250

// ---------- Bradshaw Versioning, OneBattery's declared modes ----------
//
// One-based project year from originYear (2026 = 1, 2027 = 2). Owner decision (2026-11-11):
// "November should be 1.3.x and then in Jan it'll loop back to 2.1.x". So:
//   - project year 1 (the partial origin year): months are project-relative from originMonth
//     (Aug 2026 = 0, Sep = 1, Oct = 2, Nov = 3, Dec = 4);
//   - every later year: calendar months (Jan = 1 … Dec = 12).
// The spec's project-relative mode would reset January to 0 (2.0.x), and the package's
// nextBradshawVersion uses calendar months throughout (1.11.x), so neither package helper is used.
// UPDATE starts at 1 and resets on any YEAR or MONTH change. Boundaries use scheme.timeZone.

function calendarMonth(date, timeZone) {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone, year: 'numeric', month: 'numeric' }).formatToParts(date)
  const get = (type) => Number(parts.find((p) => p.type === type)?.value)
  return { year: get('year'), month: get('month') }
}

function compareVersions(a, b) {
  const pa = a.split('.').map(Number)
  const pb = b.split('.').map(Number)
  for (let i = 0; i < 3; i++) if (pa[i] !== pb[i]) return pa[i] - pb[i]
  return 0
}

/** The `YEAR.MONTH` period for a timestamp under the scheme. */
export function bradshawPeriod(releaseAt, scheme) {
  const date = releaseAt instanceof Date ? releaseAt : new Date(releaseAt ?? Date.now())
  if (Number.isNaN(date.getTime())) throw new Error(`Invalid release timestamp: ${releaseAt}`)
  if (scheme.yearMode !== 'one') throw new Error(`OneBattery declares yearMode "one", not ${scheme.yearMode}`)
  const origin = Number(scheme.originYear)
  const originMonth = Number(scheme.originMonth)
  if (!Number.isInteger(origin) || !Number.isInteger(originMonth) || originMonth < 1 || originMonth > 12) {
    throw new Error('scheme needs integer originYear and originMonth (1-12)')
  }
  const { year: calYear, month: calMonth } = calendarMonth(date, scheme.timeZone)
  if (calYear < origin || (calYear === origin && calMonth < originMonth)) {
    throw new Error(`Release ${date.toISOString()} is before the project origin ${origin}-${originMonth}`)
  }
  const year = calYear - origin + 1
  const month = year === 1 ? calMonth - originMonth : calMonth
  return { year, month }
}

export function nextOneBatteryVersion(latestVersion, { releaseAt, scheme }) {
  const { year, month } = bradshawPeriod(releaseAt, scheme)
  const latest = String(latestVersion ?? '').trim()
  if (!latest) return `${year}.${month}.1`
  const m = VERSION_RE.exec(latest)
  if (!m) throw new Error(`Invalid three-part version: ${latest}`)
  const next = Number(m[1]) === year && Number(m[2]) === month ? `${year}.${month}.${Number(m[3]) + 1}` : `${year}.${month}.1`
  if (compareVersions(next, latest) <= 0) {
    throw new Error(`Next Bradshaw version ${next} would not sort after ${latest}; reconcile the registry explicitly.`)
  }
  return next
}

/** Mirrors app/build.gradle.kts. */
export function versionCode(version) {
  const [y, m, u] = version.split('.').map(Number)
  if (!(y >= 1 && m >= 0 && m <= 12 && u >= 1 && u <= 9999)) throw new Error(`Bad Bradshaw version ${version}`)
  return y * 1_000_000 + m * 10_000 + u
}

/** Fixed checks of the month rule and versionCode; run by `selftest` and before every prepare/check. */
export function selfTest(scheme) {
  const cases = [
    // [latest, releaseAt (UTC), expected]
    [null, '2026-08-01T07:00:00Z', '1.0.1'], // 00:00 Aug 1 in Phoenix
    ['1.0.1', '2026-08-31T12:00:00Z', '1.0.2'],
    ['1.0.2', '2026-09-15T12:00:00Z', '1.1.1'],
    ['1.1.1', '2026-10-31T02:00:28Z', '1.2.1'], // Oct 30 19:00 Phoenix (v1.0)
    ['1.2.2', '2026-11-01T06:59:59Z', '1.2.3'], // 23:59:59 Oct 31 Phoenix
    ['1.2.3', '2026-11-01T07:00:00Z', '1.3.1'], // 00:00 Nov 1 Phoenix
    ['1.3.5', '2026-11-11T16:00:00Z', '1.3.6'],
    ['1.3.6', '2026-12-01T07:00:00Z', '1.4.1'],
    ['1.4.9', '2027-01-01T06:59:59Z', '1.4.10'], // still Dec 31 in Phoenix
    ['1.4.10', '2027-01-01T07:00:00Z', '2.1.1'],
    ['2.1.1', '2027-02-10T12:00:00Z', '2.2.1'],
    ['2.11.3', '2027-12-01T07:00:00Z', '2.12.1'],
    ['2.12.1', '2028-01-01T07:00:00Z', '3.1.1'],
    ['1.3.6', '2027-03-01T12:00:00Z', '2.3.1'], // inactive months are skipped
  ]
  const failures = []
  let checks = 0
  for (const [latest, iso, want] of cases) {
    checks++
    let got
    try { got = nextOneBatteryVersion(latest, { releaseAt: new Date(iso), scheme }) } catch (e) { got = `throws: ${e.message}` }
    if (got !== want) failures.push(`${latest ?? '(none)'} @ ${iso}: want ${want}, got ${got}`)
  }
  const mustThrow = [
    ['2.1.1 in Dec 2026 (clock went backwards)', () => nextOneBatteryVersion('2.1.1', { releaseAt: new Date('2026-12-15T12:00:00Z'), scheme })],
    ['July 2026 (before origin)', () => bradshawPeriod(new Date('2026-07-31T12:00:00Z'), scheme)],
  ]
  for (const [label, fn] of mustThrow) {
    checks++
    try { fn(); failures.push(`${label} should throw`) } catch { /* expected */ }
  }
  // versionCode strictly increases across every boundary, including year 1 (Dec = 4) -> year 2 (Jan = 1).
  const seq = ['1.0.1', '1.2.1', '1.2.2', '1.3.1', '1.3.6', '1.4.1', '1.4.9999', '2.1.1', '2.9.1', '2.12.9999', '3.1.1', '99.12.9999']
  for (let i = 1; i < seq.length; i++) {
    checks++
    if (!(versionCode(seq[i]) > versionCode(seq[i - 1]))) failures.push(`versionCode ${seq[i]} <= ${seq[i - 1]}`)
  }
  for (const [v, code] of [['1.3.6', 1030006], ['1.4.1', 1040001], ['2.1.1', 2010001]]) {
    checks++
    if (versionCode(v) !== code) failures.push(`versionCode(${v}) = ${versionCode(v)}, want ${code}`)
  }
  return { checks, failures }
}

// ---------- files ----------

const config = JSON.parse(readFileSync(path.join(ROOT, 'devlog.config.json'), 'utf8'))
const registryFile = path.join(ROOT, config.registryPath || 'config/devlog-releases.json')
const versionFile = path.join(ROOT, config.versionPropertiesPath || 'version.properties')
const scheme = config.scheme
if (!scheme) throw new Error('devlog.config.json needs a scheme')

function writeAtomic(file, text) {
  mkdirSync(path.dirname(file), { recursive: true })
  const tmp = `${file}.devlog-tmp`
  writeFileSync(tmp, text, 'utf8')
  renameSync(tmp, file)
}

function readRegistry() {
  const entries = JSON.parse(readFileSync(registryFile, 'utf8'))
  if (!Array.isArray(entries)) throw new Error(`${config.registryPath} must be a JSON array`)
  const versions = new Set()
  const commits = new Set()
  for (const e of entries) {
    const where = `release ${e?.version ?? '(no version)'}`
    if (!VERSION_RE.test(e?.version ?? '')) throw new Error(`${where}: version is not YEAR.MONTH.UPDATE`)
    if (!DATE_RE.test(e.date ?? '')) throw new Error(`${where}: date must be YYYY-MM-DD`)
    if (!String(e.title ?? '').trim()) throw new Error(`${where}: title is empty`)
    if (!Array.isArray(e.notes) || !e.notes.length || e.notes.some((n) => typeof n !== 'string' || !n.trim())) {
      throw new Error(`${where}: notes must be a non-empty list of strings`)
    }
    if (e.commit != null && !SHA_RE.test(e.commit)) throw new Error(`${where}: commit must be a full 40-character SHA`)
    if (e.compatibility != null && !(e.compatibility in COMPAT_RANK)) throw new Error(`${where}: unknown compatibility`)
    if (versions.has(e.version)) throw new Error(`${where}: duplicate version`)
    if (e.commit && commits.has(e.commit)) throw new Error(`${where}: commit already recorded by another release`)
    versions.add(e.version)
    if (e.commit) commits.add(e.commit)
  }
  return entries.sort((a, b) => compareVersions(b.version, a.version))
}

function writeRegistry(entries) {
  writeAtomic(registryFile, `${JSON.stringify(entries, null, 2)}\n`)
}

function readVersionProperty() {
  if (!existsSync(versionFile)) return null
  const m = /^version\s*=\s*(\S+)\s*$/m.exec(readFileSync(versionFile, 'utf8'))
  return m ? m[1] : null
}

function writeVersionProperty(version) {
  const current = existsSync(versionFile) ? readFileSync(versionFile, 'utf8') : ''
  const next = /^version\s*=.*$/m.test(current)
    ? current.replace(/^version\s*=.*$/m, `version=${version}`)
    : `${current}${current && !current.endsWith('\n') ? '\n' : ''}version=${version}\n`
  if (next !== current) writeAtomic(versionFile, next)
}

// ---------- git ----------

function git(args) {
  try {
    return execFileSync('git', args, { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  } catch {
    return ''
  }
}

function isAncestor(a, b) {
  try {
    execFileSync('git', ['merge-base', '--is-ancestor', a, b], { cwd: ROOT, stdio: 'ignore' })
    return true
  } catch {
    return false
  }
}

function parseTrailers(body) {
  const t = { notes: [], compat: null, actions: [], release: null }
  for (const line of String(body).split(/\r?\n/)) {
    const m = /^DevLog-(Note|Compat|Action|Release):\s*(.+?)\s*$/i.exec(line)
    if (!m) continue
    const key = m[1].toLowerCase()
    if (key === 'note') t.notes.push(m[2])
    else if (key === 'action') t.actions.push(m[2])
    else if (key === 'release') t.release = m[2]
    else if (m[2].toLowerCase() in COMPAT_RANK) t.compat = m[2].toLowerCase()
  }
  return t
}

/** Commits after `from` up to and including `to`, oldest first. Only `to` when `from` is unknown. */
function commitRange(from, to) {
  const range = from && isAncestor(from, to) ? [`${from}..${to}`] : ['-1', to]
  const raw = git(['log', '--reverse', '--format=%H%x1f%s%x1f%cI%x1f%an%x1f%B%x1e', ...range])
  return raw.split('\x1e').map((r) => r.trim()).filter(Boolean).map((r) => {
    const [sha, subject, committedAt, authorName, body] = r.split('\x1f')
    return { sha, subject, committedAt, authorName, trailers: parseTrailers(body) }
  }).slice(-MAX_COMMITS)
}

function commitRangeAll() {
  const raw = git(['log', '--reverse', '--format=%H%x1f%s%x1f%cI%x1f%an%x1f%B%x1e', 'HEAD'])
  return raw.split('\x1e').map((r) => r.trim()).filter(Boolean).map((r) => {
    const [sha, subject, committedAt, authorName, body] = r.split('\x1f')
    return { sha, subject, committedAt, authorName, trailers: parseTrailers(body) }
  })
}

// ---------- release copy ----------

const ignored = (subject) => (config.ignoreCommitPrefixes || ['chore(release):'])
  .some((p) => String(subject).toLowerCase().startsWith(p.toLowerCase()))

function cleanSubject(subject) {
  const s = String(subject || '').trim()
    .replace(/^(?:feat|fix|chore|docs|refactor|test|build|ci|perf|style)(?:\([^)]*\))?!?:\s*/i, '')
    .replace(/^merge (?:pull request|branch)\b[^:]*:?\s*/i, '')
  return s ? s[0].toUpperCase() + s.slice(1) : ''
}

function buildRelease(version, head, commits, releasedAt) {
  const shown = commits.filter((c) => !ignored(c.subject))
  const notes = []
  for (const c of shown) {
    const lines = c.trailers.notes.length ? c.trailers.notes : [cleanSubject(c.subject) || 'Maintenance update.']
    for (const n of lines) if (!notes.includes(n)) notes.push(n)
  }
  const compat = shown.reduce((best, c) => {
    const k = c.trailers.compat || 'compatible'
    return COMPAT_RANK[k] > COMPAT_RANK[best] ? k : best
  }, 'compatible')
  const actions = shown.flatMap((c) => c.trailers.actions)
  const date = new Intl.DateTimeFormat('en-CA', { timeZone: scheme.timeZone, year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(releasedAt)
  return {
    version,
    date,
    releasedAt: releasedAt.toISOString(),
    title: cleanSubject(head.subject) || 'Product update',
    notes: notes.length ? notes : ['Maintenance update.'],
    commit: head.sha,
    sourceSubject: head.subject,
    author: config.author ?? null,
    includedCommits: commits.map((c) => ({ sha: c.sha, subject: c.subject, committedAt: c.committedAt, authorName: c.authorName })),
    compatibility: compat,
    action: actions.length ? actions.join(' ') : null,
  }
}

// ---------- context ----------

const truthy = (v) => ['1', 'true', 'yes', 'on'].includes(String(v ?? '').trim().toLowerCase())

function context(argv) {
  const env = process.env
  const rawAt = String(env.DEVLOG_RELEASED_AT ?? '').trim()
  const releasedAt = !rawAt ? new Date() : /^\d+$/.test(rawAt) ? new Date(Number(rawAt) * 1000) : new Date(rawAt)
  if (Number.isNaN(releasedAt.getTime())) throw new Error(`Invalid DEVLOG_RELEASED_AT: ${rawAt}`)
  return {
    production: argv.includes('--production') || truthy(env.DEVLOG_PRODUCTION),
    branch: String(env.DEVLOG_BRANCH || env.GITHUB_REF_NAME || git(['branch', '--show-current'])).trim(),
    commit: String(env.DEVLOG_COMMIT_SHA || env.GITHUB_SHA || git(['rev-parse', 'HEAD'])).trim().toLowerCase(),
    releasedAt,
  }
}

function output(result) {
  console.log(JSON.stringify(result, null, 2))
  if (process.env.GITHUB_OUTPUT) {
    appendFileSync(process.env.GITHUB_OUTPUT, `status=${result.status}\nversion=${result.version ?? ''}\n`)
  }
}

/** Binds entries whose commit is null to the commit carrying `DevLog-Release: <version>`. */
function linkEntries(entries, commits) {
  const linked = []
  for (const c of commits) {
    const v = c.trailers.release
    if (!v) continue
    const entry = entries.find((e) => e.version === v)
    if (!entry) throw new Error(`Commit ${c.sha} claims DevLog-Release ${v}, which is not in the registry.`)
    if (entry.commit && entry.commit !== c.sha) throw new Error(`Release ${v} is already bound to ${entry.commit}.`)
    if (entries.some((e) => e !== entry && e.commit === c.sha)) throw new Error(`Commit ${c.sha} already belongs to another release.`)
    if (!entry.commit) {
      entry.commit = c.sha
      entry.releasedAt ??= new Date(c.committedAt).toISOString()
      linked.push(v)
    }
  }
  return linked
}

// ---------- commands ----------

async function main() {
  const [command, ...argv] = process.argv.slice(2)
  const nextBradshawVersion = nextOneBatteryVersion

  if (command === 'selftest') {
    const { checks, failures } = selfTest(scheme)
    if (failures.length) {
      console.error(`selftest failed:\n- ${failures.join('\n- ')}`)
      process.exit(1)
    }
    console.log(`selftest ok (${checks} checks)`)
    return
  }

  if (command === 'next') {
    const latest = readRegistry()[0]
    const at = context(argv).releasedAt
    console.log(nextBradshawVersion(latest?.version ?? null, { releaseAt: at, scheme }))
    return
  }

  if (command === 'link') {
    const entries = readRegistry()
    const linked = linkEntries(entries, commitRangeAll())
    if (linked.length) writeRegistry(entries)
    output({ status: linked.length ? 'linked' : 'unchanged', version: entries[0]?.version ?? null, linked })
    return
  }

  if (command === 'notes') {
    const version = argv[0]
    const e = readRegistry().find((x) => x.version === version)
    if (!e) throw new Error(`No release ${version} in the registry`)
    const lines = [e.summary ? `${e.summary}\n` : '', ...e.notes.map((n) => `- ${n}`)]
    if (e.compatibility && e.compatibility !== 'compatible') lines.push('', `**Compatibility:** ${e.compatibility}`)
    if (e.action) lines.push('', `**Action required:** ${e.action}`)
    if (e.legacyVersion) lines.push('', `_Previously released as v${e.legacyVersion}._`)
    console.log(lines.filter((l, i) => l !== '' || i > 0).join('\n'))
    return
  }

  if (command === 'prepare' || command === 'check') {
    const st = selfTest(scheme)
    if (st.failures.length) throw new Error(`version selftest failed: ${st.failures.join('; ')}`)
    if (config.enabled === false) return output({ status: 'disabled', version: null })
    const ctx = context(argv)
    if (!ctx.production) return output({ status: 'skipped', version: readRegistry()[0]?.version ?? null, reason: 'not production' })
    if (!SHA_RE.test(ctx.commit)) throw new Error('Production release needs a full 40-character commit SHA.')
    if (!(config.productionBranches || ['main']).includes(ctx.branch)) {
      throw new Error(`Production releases are not allowed on branch ${ctx.branch || '(unknown)'}.`)
    }
    const headSubject = git(['show', '-s', '--format=%s', ctx.commit])
    if (ignored(headSubject)) return output({ status: 'skipped', version: readRegistry()[0]?.version ?? null, reason: 'generated commit' })

    const entries = readRegistry()

    if (command === 'check') {
      const newest = entries[0]
      const problems = []
      if (!newest) problems.push('registry is empty')
      else {
        if (newest.commit !== ctx.commit) problems.push(`newest release ${newest.version} is for ${newest.commit ?? 'no commit'}, not HEAD ${ctx.commit}`)
        if (readVersionProperty() !== newest.version) problems.push(`version.properties is ${readVersionProperty()}, registry is ${newest.version}`)
      }
      if (problems.length && config.failClosed !== false) {
        console.error(`DevLog check failed:\n- ${problems.join('\n- ')}`)
        process.exit(1)
      }
      return output({ status: problems.length ? 'mismatch' : 'ok', version: newest?.version ?? null, problems })
    }

    const existing = entries.find((e) => e.commit === ctx.commit)
    if (existing) {
      writeVersionProperty(entries[0].version)
      return output({ status: 'reused', version: existing.version })
    }

    const previous = entries.find((e) => e.commit)
    // First CI run (nothing bound yet): scan the whole history so the backdated release commits,
    // which carry DevLog-Release trailers, get their SHAs.
    const commits = previous ? commitRange(previous.commit, ctx.commit) : commitRangeAll().slice(-MAX_COMMITS)
    const linked = linkEntries(entries, commits)
    const head = commits.at(-1) ?? { sha: ctx.commit, subject: headSubject, trailers: parseTrailers('') }
    if (entries.some((e) => e.commit === ctx.commit)) {
      writeRegistry(entries)
      writeVersionProperty(entries[0].version)
      return output({ status: 'linked', version: entries.find((e) => e.commit === ctx.commit).version, linked })
    }
    const fresh = commits.filter((c) => !c.trailers.release)
    const version = entries.length
      ? nextBradshawVersion(entries[0].version, { releaseAt: ctx.releasedAt, scheme })
      : config.baselineVersion || nextBradshawVersion(null, { releaseAt: ctx.releasedAt, scheme })
    const release = buildRelease(version, head, fresh.length ? fresh : [head], ctx.releasedAt)
    const updated = [release, ...entries]
    if (argv.includes('--dry-run')) return output({ status: 'dry-run', version, release })
    writeRegistry(updated)
    writeVersionProperty(version)
    return output({ status: 'created', version, linked, release })
  }

  console.error('Usage: node scripts/devlog-release.mjs <next|prepare|check|link|notes <version>> [--production] [--dry-run]')
  process.exit(2)
}

main().catch((error) => {
  console.error(`devlog-release: ${error.message}`)
  process.exit(1)
})
