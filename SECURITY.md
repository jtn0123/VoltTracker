# Security Policy

Volt Tracker is a standalone Android app for the Chevy Volt. It has **no server
backend**: it talks to a Bluetooth OBD-II adapter and keeps every byte of data
on the phone in a local SQLite database. There is no account, no cloud sync, and
no telemetry upload. This document describes what data lives on the device, what
the encrypted-backup feature does and does not protect, and how to report a
vulnerability.

## Data stored on the device

All of the following is written to the app's private SQLite database
(`mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/`):

- **Location history** — full-precision GPS latitude/longitude (`REAL` columns,
  no rounding or truncation) plus GPS speed, sampled throughout every logged
  drive. This is the most sensitive class of data: it reconstructs where the
  vehicle has been.
- **OBD telemetry** — live PID readings (state of charge, voltages, temperatures,
  speed, etc.) and raw PID observations, timestamped per session.
- **Diagnostic trouble codes** — stored DTCs with module, status, and
  last-seen timestamps.
- **Vehicle identity** — the raw VIN is **never stored**. Volt Tracker keeps only
  a SHA-256 hash of the VIN, the redacted last-4 characters, and values *derived*
  from the VIN (make and model year). See
  `data/ObdStoreVehicles.kt` (`upsertVehicleFromVin`).
- **User-authored maintenance log** — free-text service entries (type/category
  and a free-text note), independent of any drive.
- **Adapter history** — Bluetooth adapter addresses the app has connected to.

Because the data is on-device, its confidentiality rests on **Android's
app-sandbox and the device lock screen**. Any party with unlocked access to the
phone, or a root/forensic capability, can read the database. Volt Tracker does
not add at-rest encryption to the live database itself.

## Encrypted backups

"Back up data" exports the full local database through the Android share sheet so
you can keep a copy elsewhere (cloud, PC). The plain export is an unencrypted
copy of the database. The **encrypted** backup path (`shareEncryptedBackup`)
protects the exported file with a passphrase. The crypto, sourced from
`mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/BackupCrypto.kt`:

- **Cipher:** AES-256 in GCM mode (`AES/GCM/NoPadding`, 256-bit key, 128-bit
  authentication tag).
- **Key derivation:** PBKDF2 with HMAC-SHA-256, **600,000 iterations** for
  backups written by current versions, over a fresh random 16-byte salt. The
  iteration count is recorded in the file header so the backup stays decryptable
  if the cost is raised again. (Older backups derived at 150,000 iterations
  remain readable.)
- **Per-file randomness:** a fresh `SecureRandom` salt and 12-byte GCM IV are
  generated for every backup, so the same passphrase produces a different key and
  ciphertext each time.
- **Integrity:** the file header (format magic, salt, IV, and iteration count) is
  authenticated as GCM additional authenticated data, so it cannot be tampered
  with — including tampering the iteration count downward to weaken the KDF.

What encrypted backup **does** protect: the confidentiality and integrity of an
*exported backup file* at rest (e.g. sitting in cloud storage or on a PC),
*provided the passphrase is strong and kept secret*.

What it **does not** protect:

- The **live on-device database**, which is not encrypted by this feature.
- A **plain (unencrypted) export** — only the encrypted path applies the cipher.
- Anything once the **passphrase is known or weak** — PBKDF2 slows brute force but
  cannot save a guessable passphrase. There is no passphrase recovery; lose it
  and the backup is unrecoverable.

## WebView bridge

The dashboard is a local Android WebView, and the JavaScript-to-native bridge is
the app's main internal attack surface. Its trust boundaries, high-risk methods,
and review rules are documented separately in
[`mobile/android/docs/bridge-threat-model.md`](mobile/android/docs/bridge-threat-model.md).

## Reporting a vulnerability

Please report security issues **privately** — do not open a public GitHub issue
for a vulnerability.

- Preferred: open a [GitHub private security advisory](https://github.com/jtn0123/VoltTracker/security/advisories/new)
  ("Report a vulnerability") on this repository.
- Alternatively, email the maintainer at **jtn0123@gmail.com** with details and
  reproduction steps.

Please allow a reasonable window for a fix before any public disclosure. This is
a hobby project maintained in spare time, so response times are best-effort.
