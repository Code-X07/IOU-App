# IOU App security audit

Reviewed on 21 November 2026 at `5bc023f` on `main`. This was a read-only review of the tracked Android application, Gradle/build configuration, GitHub workflows, tests, specifications, and review history. No application or build code was changed; this report is the only requested repository change.

The review combined the repository knowledge graph, whole-tree source/configuration inspection, three independent 5.6-generation audit passes, resolved release dependencies, targeted secret and security-pattern searches, current upstream security guidance/advisories, and fresh unit-test, lint, debug-build, and release-build verification.

| Rank | Open findings | Accepted residual risks |
| --- | ---: | ---: |
| Critical | 0 | 0 |
| Important | 1 | 0 |
| Medium | 3 | 1 |
| Low | 5 | 3 |
| Very Low | 3 | 0 |

Security findings describe a plausible confidentiality, integrity, availability, or supply-chain impact. Non-security findings are labelled explicitly. Accepted residual risks are deliberate product choices or required behavior rather than implementation defects.

## Critical

No findings. In particular, the review did not establish an authentication bypass, remotely reachable code execution path, SQL injection, exported privileged component, plaintext network transport, embedded secret, or cryptographic break in a primitive the app uses.

## Important

### F1 — Release signing runs after mutable GitHub Action references (security)

The CI and release workflows execute `actions/checkout@v4`, `actions/setup-java@v4`, and `gradle/actions/setup-gradle@v4`; CI also executes `actions/upload-artifact@v4`. These are movable tags, not immutable commit IDs. The release job subsequently handles the APK signing keystore/passwords and a `contents: write` GitHub token. A compromise of an action publisher or movement of one of those tags could therefore execute attacker-controlled code in the signing job, exfiltrate signing material, modify the APK before signing, or publish a malicious artifact.

Pin every action to a reviewed full commit SHA or a GitHub Actions dependency lockfile, retain the human-readable release tag in a comment, and automate reviewed update PRs. Protect the release job with an environment, verify the expected certificate fingerprint rather than only checking that a signature exists, and generate signed provenance. GitHub states that a full commit SHA is the immutable way to reference an action in its [secure-use guidance](https://docs.github.com/en/actions/reference/security/secure-use).

Evidence: [CI action references](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/.github/workflows/ci.yml#L24), [release action references](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/.github/workflows/release.yml#L24), [signing-secret use](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/.github/workflows/release.yml#L33), and [release permission/publishing](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/.github/workflows/release.yml#L16).

## Medium

### F2 — An unauthenticated backup header can demand excessive KDF work (security availability)

An encrypted backup supplies its own Argon2id memory, iteration, and parallelism values. The app accepts up to 256 MiB, 10 iterations, and four lanes and performs that derivation before AES-GCM can authenticate the header. A tiny crafted file with an invalid tag can consequently force a large native/heap allocation and expensive CPU work. On a memory-constrained device this can terminate the restore flow or process; repeated selections can reproduce the denial of service. The upper bound prevents a multi-gigabyte request but is still high for an Android process, and `OutOfMemoryError` is not converted to a normal restore error.

Use one supported, device-tested KDF profile for this format version rather than trusting file-selected costs, or reduce the accepted profile to a safe compatibility set. Keep a strict file-size check before derivation and add low-memory/device tests for the maximum accepted profile. A future format can encode a profile identifier rather than arbitrary work factors.

Evidence: [KDF defaults and maximum](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/data/BackupCipher.kt#L20) and [header parsing before authentication](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/data/BackupCipher.kt#L64).

### F3 — ZIP imports can exceed practical Android heap limits before rejection (security availability)

The importer accepts a 20 MiB archive, up to 50 entries, and up to 200 MiB of decompressed content at a 100:1 ratio. It eagerly buffers every ZIP entry—including unknown entries—in a `ByteArray`, while the original archive remains in memory. Known CSV entries are then converted to UTF-16 strings and expanded into row maps/domain objects. `readAtMost` itself grows a `ByteArrayOutputStream` and copies the result. A valid near-limit archive or a large unknown entry can therefore require several hundred MiB simultaneously and crash the process before row-count validation returns `LimitsExceeded`.

Reject unknown entry names before buffering; impose per-entry and per-field limits; stream each expected CSV directly into bounded validation; release raw bytes before constructing the full preview; and lower the aggregate limit to a tested Android-safe value. Add a boundary test proving that a large unknown entry is never materialized and a device test at the supported maximum.

Evidence: [import limits and eager ZIP buffering](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/io/ImportReader.kt#L25), [CSV string/map expansion](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/io/ImportReader.kt#L111), [bounded-read allocation](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/store/BackupRules.kt#L25), and [whole-document read/session retention](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/data/RealImportExportApi.kt#L117).

### F4 — A forward clock jump can auto-post an unbounded series (non-security data integrity/availability)

For each automatic recurring schedule, the runner loops until the next occurrence is after the current device date. The due view likewise materializes every outstanding date. If the wall clock moves far forward—manual change, damaged time state, or an extreme restored/emulated date—a daily schedule can generate thousands or more financial entries, perform repeated database writes and FX lookups, grow notification state, and keep the process busy for a long time. These are real ledger mutations, not just a slow preview. The risk was already noted in project handoff material but remains open.

Cap catch-up work per pass and require explicit user confirmation when the gap or occurrence count exceeds a small threshold. Detect implausible forward jumps, preserve the unprocessed boundary, and surface a recovery action rather than silently continuing. Avoid constructing the complete due list merely to display it.

Evidence: [unbounded auto-post loop](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/store/RecurringRunner.kt#L141) and [eager due-list materialization](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/ledger/views/ScheduleViews.kt#L66).

## Low

### F5 — Plaintext backups provide neither confidentiality nor authenticity (security)

The UI intentionally permits an unencrypted JSON backup after acknowledgement. It warns that anyone with file access can read the financial records, but it does not explain that the file can also be modified undetectably. Restore validates schema and ledger invariants, yet a person or storage provider with file access can make a different, internally valid ledger that the app cannot distinguish from the user's original backup. Encrypted backups use AES-GCM and do not have this problem.

Prefer authenticated encrypted backups for every restorable artifact. If plaintext export must remain, distinguish it from a trusted backup or require a stronger restore warning that explicitly covers undetectable modification. A detached MAC is useful only if its key is protected independently of the file.

Evidence: [plain/encrypted format selection and restore](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/data/BackupFormat.kt#L43), [plain-backup acknowledgement](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/ui/viewmodel/DataManagementViewModel.kt#L111), and [current warning text](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/ui/screens/DataManagementScreen.kt#L95).

### F6 — The FX client buffers an unbounded response body (security availability)

The Frankfurter client calls `bodyAsText()` and parses the complete JSON response without a byte limit or rate-count limit. HTTPS and short timeouts reduce exposure, but a compromised CA/endpoint, malicious test override, or malfunctioning provider can return a very large successful response and cause memory pressure or process termination. The server also remains a trust boundary for syntactically valid but false exchange rates; the app records the returned snapshot, so this is primarily an availability finding rather than a claim of transport tampering.

Read through a bounded channel, reject an excessive `Content-Length` or streamed byte count, cap the number of rate entries, and optionally require the expected JSON content type. The legitimate response is tiny, so a conservative ceiling is practical.

Evidence: [request and unbounded body read](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/fx/FrankfurterClient.kt#L37) and [whole-document JSON parsing](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/fx/FrankfurterClient.kt#L55).

### F7 — Gradle artifacts are not cryptographically locked (security supply chain)

The repository pins declared dependency versions and uses HTTPS repositories, but it has no strict Gradle dependency-verification metadata or dependency locks. The wrapper URL is versioned and URL validation is enabled, but `gradle-wrapper.properties` has no `distributionSha256Sum`. A compromised repository/CDN or altered artifact at an existing coordinate could therefore enter a build without a repository-owned checksum decision. Build logic has access to source, environment variables, and—in the release workflow—signing material.

Enable strict Gradle dependency verification, commit reviewed checksums/signatures and dependency locks, and add the official Gradle 8.14.3 distribution checksum. Gradle's [build security guidance](https://docs.gradle.org/current/userguide/securing_build_tool.html) recommends setting `distributionSha256Sum`; validate wrapper changes in CI.

Evidence: [repository configuration](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/settings.gradle.kts#L15), [version catalog](../../android/gradle/libs.versions.toml), and [wrapper properties](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/gradle/wrapper/gradle-wrapper.properties#L1).

### F8 — Colliding recurring IDs can route notifications to the wrong schedule (non-security correctness)

Each child notification has its own notification tag, but its `PendingIntent` identity uses only `RecurringId.value.hashCode()` as the request code and has no unique `Intent.data`. Android excludes extras from `PendingIntent` identity, and `FLAG_UPDATE_CURRENT` replaces the stored extras. Imported IDs are unconstrained strings, so known Java hash collisions such as `Aa` and `BB` can share one pending intent. Tapping either child can then open whichever schedule extra was written last. App-lock gating still applies, so this is incorrect navigation rather than an authentication bypass.

Put a stable, encoded schedule identifier in `Intent.data` (with an app-owned scheme) and keep the request code only as a secondary discriminator. Add an Android/Robolectric regression test using colliding IDs. See Android's [PendingIntent identity documentation](https://developer.android.com/reference/android/app/PendingIntent).

Evidence: [pending-intent construction](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/data/AndroidRecurringNotices.kt#L109), [unconstrained ID type](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/ledger/model/Ids.kt#L12), and [imported recurring ID construction](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/io/ImportDecoder.kt#L308).

### F9 — A malformed database pointer can prevent application startup (non-security availability)

The app-private `db-state.json` pointer is read with unbounded `readText()` and decoded without a catch or validation of the database filename. Startup runs recovery and immediately reads/uses this pointer before publishing any ledger state. A truncated file after storage corruption, restoration anomaly, or external modification on a compromised device can throw during backend startup and leave the application unusable rather than entering a recoverable error state. The marker reader already treats malformed JSON defensively, but the authoritative pointer does not.

Bound the pointer file, catch decoding/I/O failures, validate names against the generated `iou-<uuid>.db` form and containment within the database directory, then present a recovery flow. Automatic reconstruction should occur only when a single valid candidate is unambiguous.

Evidence: [pointer read](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/data/DbFiles.kt#L35) and [startup sequence](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/data/RealBackend.kt#L101).

## Very Low

### F10 — The pinned Bouncy Castle release has advisories in unused primitives (security maintenance)

The app directly pins `bcprov-jdk18on` 1.81. Upstream lists 1.81 as affected by CVE-2025-14813 (GOST CTR) and CVE-2026-5598 (FrodoKEM timing), with fixed lines including 1.84 and, for the latter, 1.81.1. The source audit found only `Argon2BytesGenerator`/`Argon2Parameters`; it does not use GOST CTR or FrodoKEM, so neither advisory supplies a demonstrated exploit path in this application. Keeping a known-affected general-purpose crypto artifact still creates scanner noise and avoidable future exposure.

Upgrade to a current compatible fixed Bouncy Castle release and rerun backup compatibility/performance tests. Upstream details are in the maintainer's [CVE-2025-14813](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902025%E2%80%9014813) and [CVE-2026-5598](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%905598) advisories.

Evidence: [pinned version](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/gradle/libs.versions.toml#L20) and [the only Bouncy Castle API use](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/data/BackupCipher.kt#L17).

### F11 — Ordinary CI does not compile the release variant (non-security release reliability)

Pull-request/main CI lints, tests, and assembles only debug variants. `assembleRealRelease` first runs in the tag-triggered release workflow, where signing configuration, release manifest merging, lint-vital behavior, and release-only packaging failures are discovered after the release process has started. This does not directly weaken the shipped binary, but it increases the chance of a late, hurried release-only failure.

Build an unsigned/non-published `realRelease` artifact on ordinary CI (or at least on protected main) without release secrets. Keep signing and publication confined to the protected release job.

Evidence: [ordinary CI task list](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/.github/workflows/ci.yml#L33) and [tag release build](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/.github/workflows/release.yml#L52).

### F12 — Recurring-runner failures are silently discarded (non-security operability)

Requested recurring passes and notification redraws catch every non-cancellation `Exception` and emit no diagnostic or user-visible state. A full disk, unexpected database error, or adapter defect can therefore leave entries unposted or notifications stale with no durable clue beyond a later retry. This behavior was previously documented, but remains open. Avoiding sensitive logs is valuable; total loss of error classification is not required for privacy.

Record a privacy-safe error category/counter and last-failure time, expose a generic status/retry action where appropriate, and retain automatic retries. Never log ledger contents, descriptions, identifiers, or passphrases.

Evidence: [swallowed pass and redraw exceptions](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/store/RecurringRunner.kt#L114).

## Accepted residual risks

### R1 — Medium: the live Room database is not app-encrypted at rest

The ledger, revisions, notes, and payment handles reside in an ordinary Room/SQLite database. App lock and `FLAG_SECURE` protect the UI, not the database file. Android application sandboxing and device file-based encryption are therefore the at-rest trust boundary; root/forensic access or access while the device is already unlocked can expose the ledger. This is an explicit MVP non-goal, with SQLCipher recorded as future work, so it is not classified as an implementation defect. Reconsider it if the threat model includes hostile device administrators, rooted devices, or post-unlock physical acquisition.

Evidence: [database construction](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/data/IouDatabase.kt#L79), [sensitive rows](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/data/Rows.kt#L12), and [documented non-goal](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/docs/superpowers/specs/2026-11-02-iou-mvp-design.md?plain=1#L45).

### R2 — Low: undo restore intentionally retains the prior ledger database

After a restore, the previous database remains on disk so “Undo last restore” can swap back to it. Android backup rules include the database domain, so the retained prior ledger may also be copied during permitted device/cloud backup. A user who believes restore immediately erased the old ledger may therefore underestimate retention. This is required for the advertised undo feature, not an orphaned-file bug. Document the retention and consider a “discard restore undo” action that securely removes the previous database when the user no longer needs it.

Evidence: [restore pointer and prior-file lifecycle](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/data/RestoreProcedure.kt#L63), [undo UI](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/ui/screens/DataManagementScreen.kt#L108), and [database backup rules](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/res/xml/data_extraction_rules.xml#L4).

### R3 — Low: screen privacy and app lock are deliberately independent

When app lock is enabled but screen protection is disabled, content can appear in screenshots/Recents; unreadable device settings deliberately fall back to app lock off; and notification re-enable timing has a documented reminder gap. These are accepted product choices recorded in project state rather than new defects. They should remain visible in the threat model and release checklist because users may otherwise assume app lock also encrypts storage or always protects OS-level previews.

Evidence: [security flag behavior](https://github.com/Code-X07/IOU-App/blob/5bc023f0d770abbde20aa8c73b668c46529dbe6a/android/app/src/main/kotlin/io/github/codex07/iou/security/AppSecurity.kt#L60), [settings fallback](../../android/app/src/main/kotlin/io/github/codex07/iou/data/FileDeviceSettings.kt), and [the prior implementation review's recorded limitations](2026-11-21-current-implementation-review.md#code-quality-and-edge-cases).

## Owner triage and resolution

Every finding was checked against the source and is accurate. The ranks above assume a capable outside attacker. For this app, most findings need the user to pick a hostile file themselves, or need GitHub, Gradle or a TLS endpoint to be compromised first. They were re-ranked on 22 November 2026 by real impact against the cost of the fix. F1–F4 and the wrapper part of F7 are fixed. The other findings are deferred: not worth fixing at this scale, and to be revisited only if the threat model or the code around them changes. The residual risks R1–R3 stay accepted product decisions.

| Finding | Re-ranked | Status | Change or reason |
| --- | --- | --- | --- |
| F1 | Medium | Fixed | Every action in `ci.yml` and `release.yml` is pinned to a full commit SHA, with the release tag kept in a comment (checkout v4.4.0, setup-java v4.9.1, setup-gradle v4.4.3, upload-artifact v4.6.2). These are the same commits the `v4` tags pointed to, so behavior is unchanged. Release environments, provenance and certificate-fingerprint checks were not added: too much process for a sideloaded app with a few users. |
| F2 | Low | Fixed | `KdfParams.MAX` is 64 MiB, 3 iterations, 1 lane: the values every backup has been written with, so no existing backup is refused. A header asking for more memory or work gives `CorruptFile` before any key is derived. Spec §7.1 updated. Test: `BackupCipherTest` "a header asking for more memory or work than the app ever writes is rejected before any key is derived". |
| F3 | Low | Fixed | `ImportReader` keeps only `manifest.json` and known table entries in memory. Any other entry is read through without being kept, but still counts toward the uncompressed and ratio limits. The uncompressed limit dropped from 200 MiB to 50 MiB. Spec §7.5 updated. Test: `ImportReaderTest` "an unknown entry is ignored but still counts against the uncompressed limits". A normalized CSV export the import would refuse is now refused too (`LimitsExceeded`, nothing written); spec §7.2. Test: `RealExportTest` "a normalized export the import would refuse is not written". Streaming CSV into validation was not done: over-engineering at this size. |
| F4 | Medium | Fixed | If more than `Recurrence.MAX_AUTO_CATCH_UP` (12) occurrences of a schedule are due when a pass starts, the runner posts none of them. They wait in the due list with `confirmToResume`, the same state a backlog gets. Once the user posts or skips (`skipOccurrences` takes a date) until 12 or fewer remain, auto-post resumes. Spec §5.7 updated. Test: `RecurringRunnerTest` "more due occurrences than the catch-up limit, as after a clock jump, wait for the user until few enough remain". No clock-jump detection or recovery screen: the threshold covers the harm. |
| F5 | Very Low | Deferred | Plain backups are a deliberate, acknowledged option. Anyone who can change the file can also read it, and the warning already covers that. |
| F6 | Very Low | Deferred | Needs a compromised HTTPS endpoint, and the 10-second request timeout already limits the response. |
| F7 | Low | Fixed | `gradle-wrapper.properties` has the official `distributionSha256Sum` for Gradle 8.14.3, checked against `services.gradle.org` and the Gradle release-checksums page. `setup-gradle` already checks the wrapper jar on CI. The fix covers the wrapper only. Strict dependency-verification metadata and lock files are not adopted: they churn on every AGP or library bump. |
| F8 | Very Low | Deferred | Needs hand-made imported IDs or a 32-bit hash collision between UUIDs. The worst case is opening the wrong schedule behind app lock. A candidate if the notifications code is touched again. |
| F9 | Very Low | Deferred | The pointer is app-private and replaced atomically. This is the same kind of risk as the accepted restore-marker I/O limit. A recovery flow would be a new component for an event that hardly ever happens. |
| F10 | Very Low | Deferred | Only Argon2 is used. Bump Bouncy Castle with the next dependency update and rerun the backup tests. |
| F11 | Very Low | Deferred | A failed release build publishes nothing; it is fixed and tagged again. A release build on every CI run would add memory pressure on a runner that has already frozen. |
| F12 | Very Low | Deferred | Deliberate: no logs that might carry ledger data. Automatic retries remain. |
| R1–R3 | As ranked | Accepted | Unchanged product decisions. |

### Targeted re-review of the fixes (22 November 2026)

The re-review confirmed F1 and F7. It raised three findings on the other fixes. All three were checked against the source.

| Finding | Rank given | Re-ranked | Status | Change or reason |
| --- | --- | --- | --- | --- |
| Exports the import may refuse (F3) | Medium | Low | Fixed | Accurate. The mismatch already existed for the 20 MiB file and 100 000-row limits; lowering the uncompressed limit only widened it. A personal ledger is far below every limit, but the fix is cheap. `RealImportExportApi.export` builds a normalized CSV export in memory and checks it with `ImportReader.limitsError`, which applies the import's size, row, entry and ratio limits without parsing the CSV. One that fails gives `LimitsExceeded` and writes nothing, as backups already do (spec §7.1). Spec §7.2 and the `ImportExportApi.export` KDoc updated. Test: `RealExportTest` "a normalized export the import would refuse is not written". XLSX exports are not re-importable, so they are not checked. |
| KDF iterations and lanes (F2) | Low | Very Low | Fixed | Accurate, though the effect is small. At the same memory, more lanes add no Argon2 work, and 10 iterations instead of 3 make a crafted restore about 3× slower and then fail. It was a one-line fix, so `KdfParams.MAX` is now 64 MiB, 3, 1, written out rather than taken from `DEFAULT` so that a lower default still reads older backups. The comment is corrected: the header is checked before the key is derived and authenticated after. The test now covers iterations and lanes. |
| Recurrence backlog built in full (F4) | Low | Very Low | Rejected | Accurate, but no real impact. The shortest pattern is weekly, so even a jump to 2100 gives about 3 900 dates per schedule: a few hundred KB, with each date computed in constant time. Notifications show one count per schedule, not one per date. The due list is a lazy list. A counting cap or paged due views would add code for no measurable gain. |

## Controls verified

- The release manifest disables cleartext traffic; the real flavor adds only internet/notification needs, and the exported debug KDF receiver is confined to `realDebug` and protected by `android.permission.DUMP`.
- App lock covers application content until biometric or device-credential success. Lifecycle timeout uses monotonic elapsed time. Notification intents do not bypass the cover.
- Encrypted backups use Argon2id and AES-256-GCM with the header as associated data. Authentication occurs before decompression; decompression is bounded.
- Restore validates a complete backup before changing the database and uses an atomic pointer/crash-recovery procedure. Import preview/commit is bound to ledger generation and sequence.
- ZIP entries are never extracted to paths, duplicate names are rejected, CSV/XLSX formula injection is escaped, Room queries are parameterized, and no production financial-data logging or third-party crash reporter was found.
- Android backup rules restrict scope to the database, exclude restore marker/temp files, and require client-side encryption capability for cloud backup where supported.
- Notification content is private and redacted when app lock is enabled; pending intents are immutable.
- No credential/API-key/private-key pattern was found in tracked application, workflow, documentation, or README files.

## Verification and limitations

- `testFakeDebugUnitTest testRealDebugUnitTest`: `BUILD SUCCESSFUL` (21 November 2026). Compilation emitted one existing test-only `ExperimentalCoroutinesApi` opt-in warning at `RealImportTest.kt:170`.
- `lintFakeDebug lintRealDebug`: `BUILD SUCCESSFUL`.
- `assembleFakeDebug assembleRealDebug assembleRealRelease`: `BUILD SUCCESSFUL`; release lint-vital also ran. The local release artifact was unsigned because release secrets were not supplied.
- `realReleaseRuntimeClasspath` resolved successfully and was manually reviewed for direct/high-risk dependencies. GitHub's Dependabot alerts endpoint reported that alerts were disabled and also that the current token lacked an administrative scope, so no Dependabot result is claimed.
- Static review cannot prove absence of vulnerabilities. No rooted-device, dynamic network interception, low-memory stress, biometric hardware, Android Auto Backup/transfer, notification collision, or malicious-provider device test was performed. Those scenarios should be covered by targeted device/instrumentation tests before treating the corresponding boundaries as fully verified.
