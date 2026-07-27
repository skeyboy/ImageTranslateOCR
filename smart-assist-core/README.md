# Smart Assist Core

Pure Kotlin contracts and deterministic logic for optional OCR context assistance.

This module deliberately does not depend on Android, the host app, model runtimes,
or network clients. It owns request/result contracts, trigger policy, deterministic
grouping and protection rules, validation, and a bounded result cache.

Run its tests independently:

```bash
./gradlew --offline :smart-assist-core:test
```

The Android app integrates this module through a host-owned adapter. Integration is
optional and disabled by default; the core module remains independently testable and
contains no Android, model-runtime, or network dependency.

See `docs/smart-assist-core-m0-m1-implementation-report.md` for the original Library
First scope and `docs/smart-assist-app-integration-report.md` for the current host
integration and validation results.
