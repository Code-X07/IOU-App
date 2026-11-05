# ADR-0011: Device-local settings live in a JSON file, not DataStore

**Date**: 2026-11-04 (recorded 2026-11-06, in the post-M1 spec sync)
**Status**: accepted
**Deciders**: project owner, Claude (M1 planning)

## Context

Spec §3.3 put the device-local settings (app lock, lock timeout, screen protection, `lastManualBackupAt`) in Jetpack DataStore. They must never leave the device: no manual backup, no Auto Backup, no restore. While building M1 Part 3, DataStore's second write failed in the local JVM tests on Windows. DataStore saves with `File.renameTo`, which on Windows can't replace an existing file. The project runs every test on the plain JVM (no Robolectric, no instrumentation tests), and the owner develops on Windows.

## Decision

`data.FileDeviceSettings` keeps the settings in `noBackupFilesDir/device-settings.json`. It reads the file once at start and replaces it atomically (temp file, then move) on every change, then updates memory; both run under `NonCancellable`. It exposes the settings as a `StateFlow` through the `store.DeviceSettingsStore` port. An unreadable file counts as the defaults.

## Alternatives Considered

### Alternative 1: Jetpack DataStore (Preferences), as the spec said
- **Pros**: standard library; handles atomic writes and coroutines.
- **Cons**: its file replacement fails on Windows JVMs, so the settings tests can't run locally; it adds a dependency for four values.
- **Why not**: local JVM tests are the project's main check, and the owner's machine is Windows.

### Alternative 2: Store device settings in the Room database
- **Pros**: no extra file; one storage mechanism.
- **Cons**: the database is the ledger. A restore would replace the settings and Auto Backup would carry them, which is exactly what spec §3.3 forbids: a restored app lock on a phone without a screen lock could lock the user out.
- **Why not**: device settings must stay outside everything that travels with the ledger.

## Consequences

### Positive
- The tests for settings, the lock policy and the backup reminder run on the plain JVM on every platform.
- A file in `noBackupFilesDir` is outside Auto Backup by its location, whatever the backup rules say.
- `settings.value` can be read synchronously at start, so the cold-start lock and the first `FLAG_SECURE` decision happen before any frame (spec §9).

### Negative
- A little hand-written persistence code (atomic replace, parse, defaults) instead of a library.
- Reads happen on the constructing thread at start (a small file).

### Risks
- A corrupted or unreadable file falls back to the defaults, including app lock off. Writes are atomic, so only outside tampering can cause this; crashing at application start instead would loop.
