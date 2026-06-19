# ADR 0008 — Encrypted-backup container format and key derivation

- **Status:** Accepted (recorded 2026-06-16; documents the existing
  `BackupCrypto` container shipped across v1/v2/v3 and the PBKDF2 cost raise from
  report item E1). Closes the "encrypted-backup crypto/format has no ADR" gap
  tracked at the end of ADR 0007 and as report item H3.
- **Deciders:** Project author.
- **Supersedes:** —
- **Superseded by:** —

## Context

Volt Tracker is an offline, server-less app: the only way data leaves the device
is an explicit, user-initiated **encrypted backup** — a single passphrase-
protected file the owner saves to cloud storage or hands to another device to
restore. That file is the one artifact that lives *outside* the app sandbox, so
it is the one place a real cryptographic decision has to be made: how to turn a
human passphrase into a key, what cipher protects the bytes, and what on-disk
format lets a future version still read a file an older version wrote.

The decision has constraints specific to this project:

- **Zero new dependencies.** The rest of the app was built without adding crypto
  libraries (see the dependency discipline noted across the feature wave), so the
  backup path must use only the platform JCE (`javax.crypto`) available on
  minSdk 23 — no Argon2/scrypt/Tink/BouncyCastle add-on.
- **Forward-compatible cost.** Password-KDF cost has to rise over time (the 2021
  OWASP PBKDF2 floor was 150k; the 2023 guidance is ~600k). Raising it must
  **not** orphan backups written at the old cost — every previously-written file
  must still decrypt after an app update.
- **Tamper-evidence, not just secrecy.** A backup is restored into the live
  database; a silently corrupted or maliciously altered file must be *rejected*,
  not partially imported.
- **Large files, bounded memory.** A backup can be tens to hundreds of MB; it
  must encrypt/decrypt as a stream, never fully buffered, and a restore must be
  bounded so a hostile file can't exhaust storage.

The threat model is deliberately scoped: the encrypted backup protects the file
**at rest and in transit** (cloud account compromise, lost device handoff, a
shared file) against an attacker who does **not** have the passphrase. It does
not try to defend against an attacker who already controls the unlocked device
and running app — the live SQLite store itself is not encrypted at rest, which
is the app's existing model and out of scope here.

## Decision

### Cipher: AES-256/GCM, streamed

- **`AES/GCM/NoPadding`, 256-bit key, 128-bit GCM tag.** GCM is authenticated
  encryption, so confidentiality and integrity come from one primitive — a
  tampered or truncated file fails the tag check on decrypt and is rejected
  rather than partially restored.
- **Streamed both ways.** Encryption wraps the output in a `CipherOutputStream`
  and decryption a `CipherInputStream`, copied through an 8 KB buffer, so a large
  backup is never held in memory in full.
- **The (key, IV) pair is never reused.** A fresh 16-byte salt and 12-byte IV are
  drawn from `SecureRandom` on **every** encryption. GCM catastrophically leaks
  plaintext XOR and loses authentication if an `(key, IV)` pair repeats; because
  the salt is also fresh, even the same passphrase derives a different key each
  time. This freshness is load-bearing and must never be cached or derived
  deterministically.

### Key derivation: PBKDF2-HMAC-SHA256, with the cost recorded in the file

- **`PBKDF2WithHmacSHA256`, 256-bit output.** Platform-native via
  `SecretKeyFactory`, so no dependency is added. The passphrase `char[]` is
  zeroed (`PBEKeySpec.clearPassword()`) in a `finally` after derivation.
- **600k iterations for new backups, 150k for legacy.** Files written from the v3
  format on derive at `ENCRYPTION_PBKDF2_ITERATIONS = 600_000` (OWASP 2023);
  v1/v2 files were written at `LEGACY_PBKDF2_ITERATIONS = 150_000`.
- **The iteration count is stored in the file (v3) and authenticated.** Raising
  the cost would normally make older files undecryptable (the key derives with a
  different count, so the GCM tag never matches). v3 therefore records the count
  it used in a 4-byte big-endian header field, and decrypt derives the key with
  the **count the file declares**. The count is fed into the GCM AAD, so it is
  tamper-evident — an attacker cannot downgrade a file to a trivially weak count
  without failing authentication. A declared count outside
  `[50_000, 5_000_000]` is rejected before any derivation, so a corrupt or
  hostile header can request neither a DoS-grade nor a trivially-weak cost.

### On-disk container format

A backup file is a plaintext header followed by the GCM ciphertext+tag:

```
+--------------------------------------------------------------+
| magic     8 bytes   "VTBKEN1\n" | "VTBKEN2\n" | "VTBKEN3\n"   |  byte[6] = format version
| salt     16 bytes   SecureRandom, PBKDF2 salt                 |
| iv       12 bytes   SecureRandom, GCM nonce                   |
| iters     4 bytes   big-endian PBKDF2 count   (v3 only)       |
| body      …         AES-256/GCM ciphertext + 16-byte tag      |
+--------------------------------------------------------------+
```

- **Versioned magic.** The format version is the 7th magic byte (`1`/`2`/`3`), so
  the reader self-identifies the layout from the first 8 bytes. `isMagic`
  accepts all three; `isEncryptedBackup` uses this to tell an encrypted backup
  from a plaintext one.
- **AAD per version.** v2 and v3 authenticate `magic ‖ salt ‖ iv` (v3 also the
  iteration field) as GCM additional-authenticated-data, binding the header to
  the ciphertext. v1 predates AAD and wrote none, so decrypt skips AAD for the v1
  magic — that back-compat skip is the reason v1 files still open.
- **Bounded restore.** Decrypt copies through `copyStream(..., maxPlaintextBytes)`
  and throws if the plaintext exceeds the caller's cap, then `fd.sync()`s the
  output so a restore is durably on disk before it is read back.

## Consequences

- **Every previously-written backup still decrypts.** v1 (no AAD, 150k), v2 (AAD,
  150k), and v3 (AAD, count-in-header) all round-trip, because the reader keys off
  the magic and the recorded count. This is the property that lets the KDF cost
  keep rising without a migration or a flag day.
- **Cost can be raised again by minting a v4-or-higher magic** (or simply writing
  a larger count in the v3 field, which already self-describes) — the format was
  designed for exactly this and the next raise needs no reader change as long as
  it stays inside the sanity bounds.
- **Security rests on passphrase strength.** PBKDF2 only slows a guessing attack;
  a weak passphrase is still brute-forceable offline. The in-app guidance steers
  the user toward a strong passphrase, and 600k iterations sets the per-guess
  cost, but this is the inherent limit of passphrase-based backup encryption.
- **Residual in-app passphrase handling risk is accepted for now.** The native
  crypto layer clears the `PBEKeySpec` password after derivation, but the
  passphrase begins in dashboard JavaScript and crosses the WebView bridge as an
  immutable string. That string cannot be reliably scrubbed from JS/WebView heap
  memory. The current threat model accepts this because the app is local,
  navigation-guarded, and protects exported backup files rather than an already
  compromised unlocked device. A future hardening pass can replace the JS prompt
  with a native password dialog so the passphrase never enters dashboard memory.
- **No key escrow / recovery.** A forgotten passphrase means an unrecoverable
  backup, by design — there is no server and no recovery key.
- **GCM's IV-uniqueness requirement is a standing invariant** enforced by the
  fresh-`SecureRandom`-per-encryption rule; the KDoc on `encryptFile` marks it as
  load-bearing so a future refactor doesn't "optimize" it into reuse.

## References

- `app/src/main/kotlin/com/volttracker/obdpoc/BackupCrypto.kt` (container,
  KDF, AAD, version fallback, restore cap)
- `app/src/main/kotlin/com/volttracker/obdpoc/DataBackup.kt` (caller: what is
  backed up and the restore flow)
- `docs/privacy-data-handling.md` (user-facing data-handling + export notes)
- `docs/adr/0007-event-notifications-and-widget-snapshot.md` (which tracked this
  ADR as the remaining open crypto/format decision)
