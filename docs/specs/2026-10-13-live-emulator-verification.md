# Live Android emulator verification

Use two distinct API36 x86_64 Android devices with the production transport and an actual local Rust relay. Pass opt-in instrumentation arguments for test host, certificate SHA256, temporary audit-only password, run identifier and sender/receiver role. No trust bypass or mock transport.

LiveRelayTest must skip explicitly when external relay arguments are absent. Positive scenario asserts READY, peer key exchange, ten shared messages, a DM, receiver disconnect/reconnect, RAM offline replay, no duplicate offline delivery and readable persisted history. The offline timing uses a bounded gap and must be corroborated by receiver state/log evidence; it is not a proof against all scheduling races. Negative scenario uses the correct pin with a wrong password, and a wrong pin with the correct password, so auth rejection cannot mask broken pinning. Timeouts fail tests.

AuditUiSetupTest runs only with an explicit isolated-device flag. It sets a local test server and screenshot permission through the production preferences APIs for actual UI/lifecycle checks. Do not run this setup on user-owned phones or overwrite existing emulator settings. Temporary test credential/certificate keys remain local and excluded from git. Test reports must distinguish this controller integration from manual UI checks, real-device testing and long-duration soak.
