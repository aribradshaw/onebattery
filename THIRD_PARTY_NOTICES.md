# Third-party notices

OneBattery's own code is © 2026 Flygon LC under the [MIT License](LICENSE).
It ships with the following third-party components.

## Fonts (bundled in `app/src/main/res/font/`)

All four are licensed under the **SIL Open Font License 1.1**. The full text is in [`licenses/OFL-1.1.txt`](licenses/OFL-1.1.txt). The files are unmodified apart from being renamed to valid Android resource names. They stay under the OFL; the MIT License does not apply to them.

| Font | Files | Copyright | Upstream |
|---|---|---|---|
| Press Start 2P | `press_start_2p.ttf` | © 2012 The Press Start 2P Project Authors (cody@zone38.net), Reserved Font Name "Press Start 2P" | https://github.com/google/fonts/tree/main/ofl/pressstart2p |
| Source Sans 3 | `source_sans_regular.ttf`, `source_sans_semibold.ttf` (Adobe's official static TTFs `SourceSans3-Regular.ttf` and `SourceSans3-Semibold.ttf` from the [3.052R release](https://github.com/adobe-fonts/source-sans/releases/tag/3.052R), `TTF-source-sans-3.052R.zip`, byte-identical) | © 2010–2023 Adobe, Reserved Font Name "Source" | https://github.com/adobe-fonts/source-sans |
| Instrument Sans | `instrument_sans.ttf` | © 2022 The Instrument Sans Project Authors | https://github.com/Instrument/instrument-sans |
| Instrument Serif | `instrument_serif.ttf` | © 2022 The Instrument Serif Project Authors | https://github.com/Instrument/instrument-serif |

## Libraries (compiled into the app, fetched by Gradle, not vendored)

| Library | License |
|---|---|
| AndroidX Core, Activity, Lifecycle, WorkManager | Apache License 2.0 |
| Jetpack Compose (UI, Material 3, Material Icons Extended) | Apache License 2.0 |
| AndroidX Graphics Path (pulled in by Compose; the only native `.so` in the APK) | Apache License 2.0 |
| Kotlin standard library and kotlinx.coroutines | Apache License 2.0 |

## Build tooling (in the repo, not shipped in the app)

| Component | License |
|---|---|
| Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) | Apache License 2.0 |

> **TODO (in-app attribution, recommended):** OFL §2 requires the copyright notice and license to accompany every copy of the fonts, and the APK is a copy. The fonts' own metadata carries the copyright line and license URL, which arguably meets this. The cleaner fix is an "Open-source licenses" entry in Settings that shows this file and `licenses/OFL-1.1.txt`. It would also cover the Apache-2.0 attribution for AndroidX.
