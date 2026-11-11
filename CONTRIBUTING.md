# Contributing to OneBattery

Thanks for helping. A few ground rules keep the project small and trustworthy.

## Reporting bugs and device data

Open an issue with:
- your phone model and Android version (and One UI or other skin version);
- what you expected and what happened;
- for capacity readings: your battery's design capacity, OneBattery's measured value, and roughly how you charged and used the phone while it measured.

Readings from **non-Samsung devices** are especially welcome. Vendors report battery current in different units and with different signs, and broad coverage is how the normalization in `BatteryReader.kt` stays correct.

## Pull requests

1. Fork the repo and branch off `main`.
2. Keep changes focused: one fix or feature per PR.
3. Build before you push: `./gradlew assembleDebug` (and `./gradlew lint` if you touched the manifest or resources).
4. Follow the existing Kotlin style (`kotlin.code.style=official`). Keep comments for *why*, not *what*.
5. Describe how you tested it, and on which device.

## Hard rules

- **No network access.** Do not add the `INTERNET` permission, analytics, crash reporters, ads or any SDK that phones home. "All data stays on the device" is a core promise of the app and is written into its Play Store privacy policy.
- **No new dangerous or restricted permissions** without an issue discussing it first. Every permission has to be justified to users and to Google Play.
- **Never commit** keystores, `keystore.properties`, `local.properties`, APKs or AABs. The `.gitignore` covers them; don't force-add them.

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE), the same license as the project.
