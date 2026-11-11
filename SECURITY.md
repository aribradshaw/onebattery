# Security policy

## Supported versions

Only the latest release on Google Play / GitHub Releases receives fixes.

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Report them privately through GitHub's **Security → Report a vulnerability** (private vulnerability reporting) on this repository.

Include the affected version, your device and Android version, and steps to reproduce. You can expect an acknowledgement within 7 days. We'll agree on a disclosure date once a fix is ready.

## Scope

OneBattery has no network access and no accounts, so the most relevant issues are:
- exported components (`PowerReceiver`) that other apps could abuse;
- leaking on-device data (the battery database, per-app usage-derived drain) to other apps;
- anything that lets the foreground service or wake lock be held in a way that drains the battery.
