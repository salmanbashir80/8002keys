# 8002Keys status — 2026-11-23

## Verified
- Owner: `salmanbashir80` (GitHub user ID `281007672`).
- Repository: `https://github.com/salmanbashir80/8002keys` — **PUBLIC**.
- Initial commits (`3f5d9fa`, `a2e0dee`, `f822f76`, `ecb8062`) preserved and merged cleanly with pinned upstream `SysAdminDoc/SwiftFloris` v1.9.70 (`e13bf0165dbfec4ff3f7d559f5f57899e6549575`, full history preserved).
- License attribution preserved: `LICENSE` (Apache-2.0), `NOTICE`, `LICENSES/`, `docs/INITIAL_REPOSITORY_README.md`, and `docs/UPSTREAM_README.md`.
- Rebranded installed identity:
  - `applicationId = "com.salmanbashir.keys8002"` (including debug builds per design spec).
  - `app_name = "8002Keys"` (root, debug, and localized `values-*/strings.xml` overrides).
  - Internal Kotlin namespace `dev.patrickgold.florisboard` preserved for IME/resource compatibility.
  - Contracts & trust gates aligned: `AppPackageContract`, `AndroidManifest.xml` (main/debug/benchmark), `app/src/main/config/trust-capabilities.json`, `scripts/check-trust-capabilities.py`, `scripts/check-fork-identity.sh`, `benchmark/BenchmarkTargets.kt`, `addons/dictionary-pack-sample`, `CrashReportFormatter`, `PrivacyPostureScreen`, `AddonsSettingsScreen`, `fastlane/` metadata, and corresponding unit tests.
- Bilingual Roman Urdu + English feature implemented:
  - `ur-PK-roman` subtype preset (`Roman Urdu (Pakistan)`, QWERTY, `pakistani_rupee` currency) in `org.florisboard.localization/extension.json` + `SubtypeDisplayNames`.
  - Curated bilingual presets (`en-US` + `ur-PK-roman` and `ur-PK-roman` + `en-US`) in `BilingualSubtypePresets`.
  - Bundled `app/src/main/assets/ime/dict/ur_roman.json` (1,189 entries) and `app/src/main/assets/freq/ur_roman.tsv`.
  - `LatinLanguageProvider`, `LatinDictionaryStore`, `ColdStartNextWordPriors`, `LatinScriptLanguageIdentifier`, `CandidateSignalAssembler`, `GlideTypingLanguageSupport`, `FirstRunLanguagePolicy`, and `TextKeyboardLayout` (Latin script rendering instead of Nastaliq for `ur-PK-roman`).

## Build & verification results (Linux VM)
- `:app:assembleDebug` **PASSED** (produces `com.salmanbashir.keys8002`, `versionCode=2119`, zero `INTERNET` permission, verified 16 KB page alignment across all 4 ABIs, verified v2 debug signature).
- `:app:verifyNoInternetPermission` & `:app:verifyNoInternetPermissionMergedDebug`: **PASSED**.
- `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`, and `:lib:kotlin:test`: **PASSED**.
- Static checks: `scripts/check-fork-identity.sh`, `scripts/check-trust-capabilities.py`, `scripts/check-locale-coverage.py --check`, `scripts/check-fastlane-metadata.sh`, `scripts/check-layout-json.py`, `scripts/check-backup-privacy-copy.sh`, `scripts/check-pointerinput-unit-allowlist.py`, `scripts/check-kotlin-build-cache-cve-guard.py`: **PASSED**.
- `:app:testDebugUnitTest` execution: **BLOCKED in cloud VM** because Maven Central returned `HTTP 429 Too Many Requests` when resolving `org.robolectric:nativeruntime-dist-compat:1.0.18` (test Kotlin compilation succeeded, runtime test execution remains to be run locally).
- Physical Android device / emulator tests: **Not run** (`sdk run` intentionally skipped per owner instruction; ready to test on device).

## Release & local testing notes
- Published APK is **debug-signed** (`C=US, O=Android, CN=Android Debug`) as a GitHub Pre-release for owner installation testing.
- Production Play/F-Droid/Obtainium distribution still requires a dedicated release keystore and physical device smoke testing of Roman Urdu typing, glide, and backup/restore.
