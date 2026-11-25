# 8002Keys

Offline Android keyboard owned by Salman Bashir (`com.salmanbashir.keys8002`), forked from [SwiftFloris](https://github.com/SysAdminDoc/SwiftFloris) (v1.9.70, commit `e13bf0165dbfec4ff3f7d559f5f57899e6549575`), derived from [FlorisBoard](https://github.com/florisboard/florisboard), and licensed under Apache-2.0 (`LICENSE`, `NOTICE`, `LICENSES/`).

## Key Features

- **Offline-first privacy posture:** Zero `INTERNET` permission (enforced by Gradle build gate `verifyNoInternetPermission` and `verifyNoInternetPermissionMerged*`), no telemetry, no analytics, and no vendor accounts.
- **English + Roman Urdu bilingual typing:** Includes a dedicated `ur-PK-roman` QWERTY preset (`Roman Urdu (Pakistan)`), curated bilingual presets (`English (US) + Roman Urdu (Pakistan)` and `Roman Urdu (Pakistan) + English (US)`), a bundled Roman Urdu lexicon (`ime/dict/ur_roman.json`) and Zipf table (`freq/ur_roman.tsv`), Roman Urdu cold-start next-word priors, statistical glide typing, and bilingual per-token language identification.
- **Full SwiftFloris v1.9.70 baseline:** Encrypted personal dictionary (SQLCipher + Tink + Android Keystore), encrypted clipboard history, incognito / `FLAG_SECURE` hardening, bilingual presets (`EN+ES`, `EN+FR`, `EN+DE`), adaptive touch model, external offline voice handoff (FUTO Voice Input), themes, snippets, and local backup/restore (supporting `8002Keys`, legacy `SwiftFloris`, and `FlorisBoard` archives).

## Trust Boundaries & Declared Permissions

Machine-readable trust registry: [`app/src/main/config/trust-capabilities.json`](app/src/main/config/trust-capabilities.json).

Declared manifest permissions:

- `android.permission.INTERNET` — Opt-in user-authenticated Google Gemini and OpenAI ChatGPT AI grammar, spell-check, and writing assistant (gated by `SensitiveFieldGuard`).
- `android.permission.REQUEST_INSTALL_PACKAGES` — User-initiated 1-tap in-app APK auto-update installation from GitHub Releases.
- `android.permission.VIBRATE` — Optional keypress haptic/vibration feedback.
- `android.permission.POST_NOTIFICATIONS` — User-visible local notifications on Android 13+.
- `android.permission.READ_CALENDAR` — Explicit opt-in calendar quick-insert for local agenda entries.
- `android.permission.QUERY_ADVANCED_PROTECTION_MODE` — Read-only Android 16+ Advanced Protection Mode check so learning, clipboard history, and addon enrollment pause when active.
- `com.salmanbashir.keys8002.permission.BIND_MCP` — Signature-protected binding for a trusted local MCP daemon.

Extension & runtime disclosures:

- **MCP & Addons:** MCP daemon packages are a separate trust boundary, and enrollment enforces an explicit permission allowlist so a package may hold only allowlisted permissions (no `INTERNET` or network-state permissions, verified before certificate trust or binding).
- **Optional Runtimes:** None of the Bergamot, LiteRT-LM, handwriting, or local voice recognizer runtimes currently ships in the base APK; live Smart Compose remains an opt-in local n-gram heuristic, and voice input delegates to external offline voice IMEs such as FUTO Voice Input.

## Project Documentation & History

- [Status, verified checks & build notes](docs/8002KEYS_STATUS.md)
- [Initial repository README](docs/INITIAL_REPOSITORY_README.md)
- [Upstream SwiftFloris README](docs/UPSTREAM_README.md)
- [Threat Model](docs/THREAT_MODEL.md)
- [Privacy & AI Explainer](docs/PRIVACY_AND_AI.md)
- [Security Policy](docs/SECURITY.md)
