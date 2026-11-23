# 8002Keys bootstrap status — 2026-11-23

## Verified
- Owner: salmanbashir80 (GitHub user ID 281007672).
- Repository: https://github.com/salmanbashir80/8002keys — PRIVATE.
- main and develop exist. Initial README commit is preserved.
- Foundation: https://github.com/SysAdminDoc/SwiftFloris
- Pinned upstream commit: e13bf0165dbfec4ff3f7d559f5f57899e6549575 (v1.9.70).
- Full upstream history cloned locally: 1,228 commits.
- LICENSE is Apache-2.0. NOTICE and LICENSES include FlorisBoard, SCOWL,
  wordfreq, Tink, profanity-list and JetPref attribution. Preserve these and all
  file-specific license notices during import.
- Source dependency catalog inspected: AGP 9.3.2, Kotlin 2.4.21,
  Compose BOM 2026.08.00, KSP 2.3.11, Room 2.8.4, SQLCipher 4.20.0,
  Tink 1.23.0; Gradle wrapper 9.7.1 with a pinned SHA-256.
- Baseline SDK requirements: min 26, target 36, compile 37, build-tools 37.0.0.
- Upstream's static fork-identity check passed. Bootstrap workflow YAML parses.
  These are NOT evidence of a successful Android build or runtime behavior.
- Original source worktree remains unmodified. No proprietary SwiftKey code,
  user dictionaries, keystroke data, AI keys or signing credentials imported.

## Blocking evidence
Cloud terminal:
- GitHub CLI device approval succeeded in the browser, but terminal authentication
  did not finish: execution policy blocked https://api.github.com:443.
- Gradle download/configuration made progress after proxy configuration and SDK
  setup, but execution policy blocked https://plugins.gradle.org:443.
- Android compilation and unit tests did not complete. No APK was produced.

GitHub Actions:
- Run: https://github.com/salmanbashir80/8002keys/actions/runs/45133924438
- Workflow commit: a2e0dee8c9e77097314b5260e7e5d13906c09d61
- Conclusion: failure; baseline job did not start; no artifacts.
- GitHub annotation: "The job was not started because recent account payments
  have failed or your spending limit needs to be increased. Please check the
  'Billing & plans' section in your settings"
- Billing settings and repository visibility have not been changed.

## Resume sequence
1. Resolve the account's GitHub Actions billing/spending-limit block, then rerun
   the bootstrap workflow. This is an account-owner financial decision.
2. Verify the pinned upstream's assembleDebug, testDebugUnitTest and lintDebug.
   The workflow imports nothing if those gates fail.
3. On successful gates, the workflow merges upstream history into this repository,
   preserves the initial README in docs/INITIAL_REPOSITORY_README.md, and keeps
   upstream's README as historical docs/UPSTREAM_README.md. Pushes to main and
   develop are atomic and fast-forward only. Existing Android projects stop import.
4. Rebrand display name to 8002Keys and installed ID to com.salmanbashir.keys8002.
   Preserve internal source namespace for IME class/resource compatibility.
   Update manifest contracts, trust registry, addon fixtures and tests together.
5. Add regular push/PR/manual Android build checks, APK artifacts and checksums.
6. After verified builds only, publish an explicitly debug-signed prerelease with
   commit, checksums, test results and known issues. Production needs secure
   signing and successful device testing.

Rebranding, Roman Urdu features, AI, translation and production signing have not
been implemented. Source import into GitHub, a functioning 8002Keys build and
a prerelease are still pending; this document must not be read as completion.
