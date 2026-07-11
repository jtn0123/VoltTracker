# ADR 0009 — Portable vehicle identity across reinstall and restore

- **Status:** Accepted (recorded 2026-07-11; fixes graded-audit item B8 —
  vehicle history forking after a reinstall or a second-phone restore).
- **Deciders:** Project author.
- **Supersedes:** —
- **Superseded by:** —

## Context

Vehicle rows are keyed by `vehicle_key = HMAC-SHA256(secret, VIN)`
(`VinKeyHasher`), where `secret` is a random 32-byte value generated per
install and kept in app-private SharedPreferences. The raw VIN is deliberately
**never stored** — the `vehicles` row carries only the redacted last-4
(`vin_redacted`) and the keyed hash. This is a real privacy property: the VIN
space is small and highly structured (known WMI prefixes, check digit, visible
last-4), so an *unsalted* hash is enumerable by dictionary — which is exactly
why the earlier plain `SHA-256(VIN)` key (`VinKeyHasher.legacyHash`) was
retired in favor of the keyed HMAC, and why a unit test pins that the stored
key is not the unsalted hash.

The per-install secret is what breaks identity portability. Android auto-backup
is disabled (`allowBackup="false"`), so after an uninstall/reinstall — or on a
second phone — a fresh secret is generated and the **same physical car hashes
to a different `vehicle_key`**.

Two portability mechanisms already exist (shipped with the daily-workflows
hardening wave, #308):

1. **Secret transport.** The backup settings manifest (`volt_backup_settings`,
   a transport-only table embedded in both plain and encrypted backups)
   carries the install's identity secrets (`vehicleIdentityKeys`, primary +
   previously imported, capped at 8). On restore they are imported as
   *secondary* secrets — the local primary never changes, and no secret ever
   leaves the device except inside the user's own backup file.
2. **Connect-time healing.** `ObdStoreVehicles.upsertVehicleFromVin` matches
   an incoming live VIN against `hashCandidates` (the VIN hashed under every
   known secret, plus the legacy unsalted hash), consolidates any duplicate
   rows it finds (remapping `vehicle_id` references), and normalizes the
   surviving row to the local primary key.

The remaining gap — the actual B8 defect — is **merge time**.
`DatabaseMerger.copyVehicles` matches donor rows to live rows strictly by
`vehicle_key` equality. When the same car exists on both sides under different
secrets, the merge inserts a second `vehicles` row:

- Per-vehicle history (trips, charge sessions, battery snapshots, field
  capabilities, exports) forks across two vehicle ids.
- Child-row dedupe that keys on `vehicle_id` (e.g. `field_capabilities`)
  cannot match across the fork, so duplicates slip in and survive even after a
  later consolidation.
- The fork only heals at the *next successful live VIN read* — which may be
  never on a device that browses history without the car (VIN reads also fail
  in the field), and every re-merge before that recreates the duplicate.

A hard constraint shapes the fix: at merge time neither database contains the
VIN, only HMACs under different keys. Without the VIN there is **no
cryptographic way** to decide that `HMAC(s_A, VIN)` and `HMAC(s_B, VIN)` name
the same car — matching is only possible using information recorded when the
VIN was actually in hand.

## Decision

Complete the secret-transport design with **recorded key aliases**: whenever
the app has the real VIN (a live OBD read), it writes down what that vehicle's
key *would be* under every identity secret it knows, so later merges can match
by key-set intersection without ever needing the VIN again.

### Schema: additive `vehicle_key_aliases` column (v16)

`vehicles` gains a nullable TEXT column `vehicle_key_aliases` holding a JSON
array of HMAC vehicle keys (capped at 16, deduplicated). Migration v15→v16 is
a single guarded `ALTER TABLE … ADD COLUMN`, following the existing
additive-only style; existing rows keep `NULL` and behave exactly as before.

### Write path: `ObdStoreVehicles.upsertVehicleFromVin`

With the VIN in hand, the upsert:

- computes the alias set = VIN hashed under **every** known secret (local
  primary + imported), *excluding* the legacy unsalted hash (storing it would
  reintroduce the enumerable hash the HMAC migration removed);
- matches existing rows by `vehicle_key` **or** alias intersection (all rows
  are loaded — a user has a handful of cars at most);
- on consolidation, folds the deleted duplicates' keys and aliases into the
  surviving row's alias set (minus the legacy hash) and keeps the earliest
  `first_seen_ms`, so identity lineage and history continuity survive; and
- stores the alias set on newly inserted rows.

### Merge path: `DatabaseMerger.copyVehicles`

The merger matches a donor vehicle to a live vehicle when their
`{vehicle_key} ∪ aliases` sets intersect. On a match it remaps ids as before
and additionally unions the alias sets, takes `min(first_seen_ms)` and
`max(last_seen_ms)` — so the lineage keeps propagating through future backups.
No intersection → insert, exactly as today (the donor row keeps its aliases,
so the next live VIN read or the next merge can still consolidate it).

### What this achieves per scenario

- **Reinstall, restore old backup into the fresh (empty) install:** the merge
  imports the single donor row — one vehicle, continuous history immediately.
  The manifest import plus connect-time healing then normalize the key to the
  new install's secret on the next drive. (This path already worked; it is now
  pinned by tests.)
- **Reinstall where the car was connected before restoring / second phone:**
  the first merge cannot match (neither side has ever seen the other's secret
  together with the VIN — the cryptographic constraint above), so it forks
  once and heals at the next VIN read, as today. But that healing now records
  aliases under both secrets, so **every subsequent merge in either direction
  matches at merge time** — the recurring two-phone sync workflow stops
  re-forking on every exchange.
- **No-VIN vehicle rows** (never produced by the current writer, which
  requires a 17-char VIN, but possible in old/foreign data): no aliases, so
  they fall back to today's exact `vehicle_key` match.

## Privacy analysis

- **What is stored where:** the DB gains only additional HMAC-SHA256 outputs
  (aliases) in the same trust domain as `vehicle_key` itself. The raw VIN is
  still never stored; the legacy unsalted hash is still never stored (aliases
  explicitly exclude it, and the existing enumeration-resistance test keeps
  pinning the key columns).
- **What leaves the device:** nothing new. Aliases are keyed by secrets that
  already travel inside the user's own backup file (the settings manifest,
  shipped since #308); an alias is exactly as sensitive as the `vehicle_key`
  column that has always been in the backup. An attacker holding a backup file
  could already dictionary-test VINs against `vehicle_key` using the manifest
  secrets, and the encrypted backup (ADR 0008) remains the recommended
  transport for precisely that reason.
- **Secrets remain device-generated and random.** No key derivation from the
  VIN, no fixed salt, no server, no telemetry.

## Alternatives considered

1. **Match by raw VIN at merge time.** Cleanest semantics, but the VIN is not
   in the database — by design — and adding it (even only to the backup)
   breaks the documented "the full VIN is never stored" stance for a case the
   alias mechanism covers. Rejected.
2. **Derive `vehicle_key` deterministically from the VIN alone** (unsalted or
   fixed-salt hash). Fully portable, but reintroduces the enumerable-hash
   problem the HMAC migration deliberately fixed (VIN space is dictionary-
   sized; a fixed salt is public knowledge once the APK ships). Rejected as a
   privacy regression against ADR-level intent and an existing pinning test.
3. **Adopt the donor's secret as the local primary on restore-to-empty.**
   Helps only the empty-install case (which already ends with one row), adds a
   mutable-primary-secret state machine, and does nothing for merges between
   two live installs. Rejected as complexity without coverage.
4. **Heuristic match on `vin_redacted` last-4 + make + model year.** Available
   at merge time with no schema change, but a false positive silently merges
   two different cars' histories — catastrophic and unrecoverable in the app's
   most sensitive data-integrity area. Rejected.
5. **Status quo (connect-time healing only).** Leaves the merge fork, the
   `vehicle_id`-keyed dedupe misses, and the re-fork on every sync. Rejected —
   that is the B8 defect.

## Migration / compatibility

- **Old backups restore correctly.** Staged backups are migrated in place to
  the current schema (`BackupMigrator` reuses `onUpgrade`), so a pre-v16 file
  gains the column as `NULL` and merges through the strict-key fallback —
  byte-for-byte today's behavior. The merger also tolerates donors missing the
  column outright (`availableColumns` filter).
- **Newer-than-app backups** are still refused (`TOO_NEW`), unchanged.
- **Downgrade:** a v16 database opened by an older app fails SQLiteOpenHelper's
  version check as with any prior bump; backups remain the supported transport.
- **Merger column-list sync** is enforced by the existing schema-pinning test
  (`donorColumnListsStayInSyncWithTheLiveSchema`), updated with the column.

## Consequences

- Two-phone and backup-sync workflows converge to a single vehicle row at
  merge time once any install has seen the car with the peer's secret
  imported; the one remaining fork window (two installs that never exchanged
  secrets, merged before any reconnect) heals on the next VIN read exactly as
  before, and the UI's existing "reconnect to finish tidying up" merge message
  covers it.
- Vehicle identity lineage is self-contained in the database, so it survives
  paths that bypass the settings manifest (the manifest remains the way the
  *local* install learns donor secrets for future alias writes).
- `field_capabilities`-style duplicates can no longer be created by the
  merge-time fork in the covered scenarios; duplicates created by the residual
  connect-time consolidation path are unchanged (pre-existing behavior).

## References

- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/VinKeyHasher.kt`
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreVehicles.kt`
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/DatabaseMerger.kt`
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/BackupSettingsManifest.kt`
- `mobile/android/docs/privacy-data-handling.md`
- ADR 0008 — encrypted-backup container format (threat model for the file at rest)
