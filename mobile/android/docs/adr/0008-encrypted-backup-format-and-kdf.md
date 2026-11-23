# ADR 0008 — Encrypted-backup container format and key derivation

- **Status:** Accepted (recorded 2026-06-16; documents the existing
  `BackupCrypto` container shipped across v1/v2/v3 and the PBKDF2 cost raise from
  report item E1). Closes the "encrypted-backup crypto/format has no ADR" gap
  tracked at the end of ADR 0007 and as report item H3. Amended 2026-11-23 for
  the segmented v4 container, which new backups now use.
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

### Cipher: AES-256/GCM, in fixed-size segments

- **`AES/GCM/NoPadding`, 256-bit key, 128-bit GCM tag.** GCM is authenticated
  encryption, so confidentiality and integrity come from one primitive — a
  tampered or truncated file fails the tag check on decrypt and is rejected
  rather than partially restored.
- **One GCM operation per 1 MiB segment (v4).** Android's platform GCM cipher
  (Conscrypt) returns nothing from `update()` and buffers every byte until
  `doFinal()`, so v1-v3, which ran one GCM operation over the whole file, held
  the whole backup on the heap two to three times over and, on a typical 256 MB
  app heap, ran the app out of memory once the database passed roughly 100 MB. v4 seals the plaintext in
  segments, each with its own tag and its own `doFinal()`, and writes each one
  out before reading the next, so memory stays at about one segment at any
  backup size. This uses only the platform cipher; Tink's `StreamingAead` is the
  off-the-shelf equivalent but would add a dependency.
- **Segment nonce and AAD.** A segment's 12-byte nonce is the file's random
  8-byte nonce prefix followed by the segment index as a big-endian uint32. Its
  AAD is the whole 40-byte file header followed by one flag byte, `1` for the
  final segment and `0` for every other. So a segment that is reordered,
  duplicated or dropped is opened under the wrong nonce; a segment moved from
  another file fails under that file's key, prefix and header; and a file cut at
  a segment boundary, or extended past its final segment, fails because the
  segment read as last was not sealed as last. The final segment is always
  present (0 to 1 MiB of plaintext; an empty database is one empty segment).
- **The (key, nonce) pair is never reused.** A fresh 16-byte salt and a fresh
  nonce prefix (v4) or 12-byte IV (v1-v3) are drawn from `SecureRandom` on
  **every** encryption, and within a v4 file each segment's index makes its
  nonce unique. GCM catastrophically leaks plaintext XOR and loses
  authentication if a `(key, nonce)` pair repeats; because the salt is also
  fresh, even the same passphrase derives a different key each time. This
  freshness is load-bearing and must never be cached or derived
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

New backups are written as v4: a 40-byte plaintext header followed by the
sealed segments.

```
+--------------------------------------------------------------+
| magic     8 bytes   "VTBKEN4\n"                               |  byte[6] = format version
| salt     16 bytes   SecureRandom, PBKDF2 salt                 |
| prefix    8 bytes   SecureRandom, segment nonce prefix        |
| iters     4 bytes   big-endian PBKDF2 count                   |
| segsize   4 bytes   big-endian plaintext bytes per segment    |  1 MiB; reader accepts 1 KiB-16 MiB
| segments  …         each: ciphertext + 16-byte tag; the last  |
|                     holds 0..segsize bytes of plaintext       |
+--------------------------------------------------------------+
```

v1-v3 files, still readable, are a plaintext header followed by one GCM
ciphertext+tag over the whole database:

```
+--------------------------------------------------------------+
| magic     8 bytes   "VTBKEN1\n" | "VTBKEN2\n" | "VTBKEN3\n"   |  byte[6] = format version
| salt     16 bytes   SecureRandom, PBKDF2 salt                 |
| iv       12 bytes   SecureRandom, GCM nonce                   |
| iters     4 bytes   big-endian PBKDF2 count   (v3 only)       |
| body      …         AES-256/GCM ciphertext + 16-byte tag      |
+--------------------------------------------------------------+
```

- **Versioned magic.** The format version is the 7th magic byte (`1`-`4`), so
  the reader self-identifies the layout from the first 8 bytes. `isMagic`
  accepts all four; `isEncryptedBackup` uses this to tell an encrypted backup
  from a plaintext one.
- **v4 keeps the v3 key derivation.** Same salt field, same recorded and
  authenticated iteration count with the same bounds, same PBKDF2 call; only the
  IV field becomes the nonce prefix and the segment size is added. A segment
  size outside 1 KiB-16 MiB is rejected before any derivation, so a hostile
  header cannot make the reader allocate an absurd buffer.
- **AAD per version.** v4 authenticates the whole header plus the last-segment
  flag in every segment. v2 and v3 authenticate `magic ‖ salt ‖ iv` (v3 also the
  iteration field) as GCM additional-authenticated-data, binding the header to
  the ciphertext. v1 predates AAD and wrote none, so decrypt skips AAD for the v1
  magic — that back-compat skip is the reason v1 files still open.
- **Bounded restore.** Decrypt counts plaintext as it is written and throws if it
  exceeds the caller's cap, then `fd.sync()`s the output so a restore is durably
  on disk before it is read back. Each v4 segment is authenticated before its
  plaintext is written; on any failure the partial output is deleted.
- **Memory precheck.** Before work starts, `BackupCrypto` compares what it needs
  with the free heap (`Runtime.maxMemory()` minus used) and throws
  `InsufficientMemoryException` instead of running out of memory: about four
  segments for v4, and three times the file size for a v1-v3 file, which can
  only be verified by holding it whole. `DataBackup` maps that, and any
  `OutOfMemoryError` from encrypt or decrypt, to a failed backup or the
  `TOO_LARGE_FOR_MEMORY` restore status, so the app reports it instead of
  crashing. A large older backup that this phone cannot open may still open on
  a phone with more memory.
- **No half-written file.** A failed or interrupted encryption deletes its
  output, and a file cut short by a killed process has no final segment, so it
  can never decrypt as a complete backup.

## Consequences

- **Every previously-written backup still decrypts.** v1 (no AAD, 150k), v2 (AAD,
  150k), and v3 (AAD, count-in-header) all round-trip, because the reader keys off
  the magic and the recorded count, as long as the phone has the heap for a
  single-shot decrypt; v4 has no such limit. This is the property that lets the KDF cost
  keep rising without a migration or a flag day.
- **Cost can be raised again by minting a v5-or-higher magic** (or simply writing
  a larger count in the v4 field, which already self-describes) — the format was
  designed for exactly this and the next raise needs no reader change as long as
  it stays inside the sanity bounds.
- **Security rests on passphrase strength.** PBKDF2 only slows a guessing attack;
  a weak passphrase is still brute-forceable offline. The in-app guidance steers
  the user toward a strong passphrase, and 600k iterations sets the per-guess
  cost, but this is the inherent limit of passphrase-based backup encryption.
- **No key escrow / recovery.** A forgotten passphrase means an unrecoverable
  backup, by design — there is no server and no recovery key.
- **GCM's nonce-uniqueness requirement is a standing invariant** enforced by the
  fresh-`SecureRandom`-per-encryption rule and the per-segment index; the comment
  in `encryptFileSegmented` marks it as load-bearing so a future refactor doesn't
  "optimize" it into reuse.

## References

- `app/src/main/kotlin/com/volttracker/obdpoc/BackupCrypto.kt` (container,
  KDF, AAD, version fallback, memory precheck, restore cap)
- `app/src/main/kotlin/com/volttracker/obdpoc/BackupSegmentStream.kt` (v4
  segment nonce, AAD and framing)
- `app/src/main/kotlin/com/volttracker/obdpoc/DataBackup.kt` (caller: what is
  backed up and the restore flow)
- `docs/privacy-data-handling.md` (user-facing data-handling + export notes)
- `docs/adr/0007-event-notifications-and-widget-snapshot.md` (which tracked this
  ADR as the remaining open crypto/format decision)
