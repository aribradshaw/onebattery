# Changelog

All notable changes to OneBattery. Versions before the first public release were sideloaded test builds. Their notes are reconstructed from build history, so they are approximate.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). From the first public release on, versions follow Bradshaw Versioning `YEAR.MONTH.UPDATE` (see `docs/VERSIONING.md`; `versionCode = YEAR×1,000,000 + MONTH×10,000 + UPDATE`). The test builds below kept their original `1.x` names (`versionCode` in parentheses).

## [Unreleased] (first Play release, currently `1.3.6` / 1030006)
- Launcher icon follows the selected theme.
- Reworked history cards and navigation, an interval dial for notification cadence, and a charging animation.
- Release signing via `keystore.properties`. Open-source repository setup (license, docs, Play listing metadata).

## [1.6] (7) — 2026-11-10
- Four Flygon LC house themes: **Flygon LC**, **Othello**, **System Boring** and **Night**, with bundled fonts.
- Themed adaptive launcher icons (including Android 13+ monochrome).

## [1.5] (6) — 2026-11-10
- Theme set revisions (interim build).

## [1.4] (5) — 2026-11-10
- First theme picker. Status and navigation bar icons adapt to light and dark themes.

## [1.3] (4) — 2026-11-08
- Notification settings: live readout on/off, update cadence, and pause updates while the screen is off.

## [1.2] (3) — 2026-11-05
- **What's draining your battery:** per-app drain from measured current while the screen is on, an idle bucket for screen-off time, and Android's records of apps busy in the background. Requires Usage access.

## [1.1] (2) — 2026-10-30
- **Measured capacity** by coulomb counting. A health card shows the percentage of design capacity, with a trend chart.
- Partial wake lock while plugged in, for gap-free charge measurements.
- Monitoring restarts after reboot or app update.

## [1.0] (1) — 2026-10-30
- First build: live battery level, status, power, voltage and temperature. Level and temperature history. Background sampling with a foreground monitor service.
