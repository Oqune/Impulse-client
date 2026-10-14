# Android auth, outbox, and instrumentation hardening

Date: 2026-10-13
Status: Approved for implementation by project owner
Scope: Android client only. Preserve all existing app behavior and Wire v3 bytes. No commit, push, or version change in this task.

## Context

The 2026-10-13 Android static audit found three actionable client issues: AuthChallenge Argon2id parameters have a lower bound but no upper admission limits; failed outbox entries are discarded after three retries; and two Android instrumentation tests do not isolate the storage they assert against or use valid certificate hash fixtures. The lock/lifecycle, replay, peer-identity UX, and other audit findings are outside this spec.

## Goals

1. Reject unreasonably expensive Argon2id AuthChallenge tuples before invoking KDF code, preserving the OWASP minimum and server defaults.
2. Keep queued encrypted frames durable across repeated transport failure and resume flushing on a later READY/reconnect event, without changing Wire v3 or falsely reporting delivery acknowledgment.
3. Make the existing certificate-manager and DAO instrumentation scenarios exercise valid, isolated data and assertions that match the actual Room uniqueness contract.
4. Add narrow regression coverage for the changed parsing and outbox retry behavior.

## Non-goals

- No Wire v3 opcode, frame layout, cryptographic primitive, or server changes.
- No change to `SecureKeyManager` backup KDF or stored-key formats.
- No biometric lifecycle changes.
- No QUIC/emulator transport changes; handled separately by the project owner.
- No new repository-wide dependency injection or broad architecture refactor.
- No claim of durable server delivery acknowledgment: existing transport reports stream-write success only.

## Requirements

### Argon2id challenge admission

- Continue enforcing the OWASP floor: `m >= 47104 KiB`, `t >= 3`, `p >= 1`.
- Use maximum accepted values `m <= 65536 KiB`, `t <= 6`, and `p <= 2`, limiting unauthenticated resource admission to 64 MiB, six passes, and two lanes while preserving the OWASP floor.
- Reject an out-of-range tuple instead of silently reducing it, so client/server key derivation cannot diverge.
- Preserve compatibility with server defaults and legacy challenge frames that omit the optional tuple (defaults remain `47104/3/1`).
- Ensure malformed numeric fields continue to fail closed. Authentication must not start Argon2 with a rejected tuple.
- Add JVM unit tests for omitted/default tuple, valid lower/upper boundaries, and each over-limit field.
- Exercise the maximum admitted tuple through the actual Bouncy Castle Argon2 derivation and assert the expected 32-byte result (without a machine-dependent timing threshold).
- Confirm the parsed challenge's salt and tuple feed `buildAuth` exactly; a different salt or supported-but-different tuple must produce a different proof.

### Durable outbox retry

- Do not delete a queued frame because a bounded number of sends failed.
- On send failure, preserve the entry and its persisted representation, exit the current flush attempt, and rely on the existing READY/reconnect trigger to resume.
- On successful stream write, remove the entry and update persistence as today.
- Keep frames encrypted as they are today and do not persist plaintext.
- Avoid duplicate flush loops and preserve per-server isolation.
- Add regression coverage through a narrow, testable retry decision/helper seam; do not add a large dependency-injection framework.

### Instrumentation scenarios

- `TrustedCertManagerTest` must use valid 64-character SHA-256 hex strings and unique IDs/cleanup so global Android Keystore preferences do not make tests order-dependent.
- `MessageDaoTest` must assert purge against the in-memory DAO that received the inserts; do not use the production `MessageRepository(context)` for rows held in another database.
- Optimistic/authoritative message rows are distinct under the current unique index `(server_id, server_msg_id)`. Tests must assert the actual DAO contract rather than expecting different IDs to collapse.
- Keep Room schema and production repository APIs unchanged unless implementation proves a narrow seam is required.

## Acceptance criteria

- Source and tests compile in the project test gate run by the coordinating agent.
- Regression tests demonstrate the Argon2 admission boundaries and persistence after repeated outbox send failures.
- Android instrumentation tests no longer use invalid cert hashes or cross-database assumptions.
- `client/app/src/main` remains Wire v3-compatible and no protocol bytes change.
- No files outside `client/` are changed by implementation; this root audit report is the separately authorized audit deliverable.
