# 8002Keys baseline

User-approved scope: bootstrap the private salmanbashir80/8002keys repository from
SysAdminDoc/SwiftFloris, preserve history and attribution, build/test upstream
before rebranding, and publish only a verified debug prerelease.

Use the existing Kotlin/Compose IME and Gradle structure. Set installed identity
to com.salmanbashir.keys8002 and display name to 8002Keys. Retain the internal
source namespace dev.patrickgold.florisboard so resources, manifest class names,
and upstream merges remain compatible. Debug builds use the requested exact ID;
production signing is explicitly deferred. Keep existing offline behavior and
no INTERNET permission. Add no cloud AI, keys, telemetry, or keystroke logging.

Preserve LICENSE, NOTICE, LICENSES and existing copyright headers. Preserve the
original README as docs/UPSTREAM_README.md, clearly marked historical upstream
material. Use a new accurate README for this fork. Preserve the initial GitHub
README commit by merging unrelated histories, never force-pushing main.

Alternatives: a source-only snapshot loses useful history; a ground-up IME
unnecessarily discards the chosen foundation. Full-history import with a small
identity patch is the selected approach specified by the user.

CI: build debug APK, unit tests and lint on main/develop/PRs; checksums and reports
as artifacts. No release on failed gates. Prerelease only after verified build,
explicitly identify debug signing, device-test gaps and pending features.

Later milestones: verify inherited typing/autocorrect, implement and evaluate
Roman Urdu + English, then swipe, clipboard, themes, external voice handoff,
optional consent-based AI/replies and translation, and privacy controls. Existing
upstream claims are not evidence of verified 8002Keys functionality.
