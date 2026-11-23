# 8002Keys

Private Android keyboard project owned by Salman Bashir.

- **Planned app name:** 8002Keys
- **Planned application ID:** `com.salmanbashir.keys8002`
- **Foundation:** [SwiftFloris](https://github.com/SysAdminDoc/SwiftFloris),
  derived from FlorisBoard; Apache-2.0 with all upstream/asset notices preserved.

## Current status
Repository and branches exist. The pinned upstream source has been inspected
and cloned locally with history, but the Android baseline build is **blocked**.
GitHub Actions refused to start because of an account billing/spending-limit
error; local execution also encountered network-policy blocks.

The bootstrap workflow will build, test and lint upstream before importing its
history. No 8002Keys APK, implemented features or prerelease is claimed yet.

See [verified results, blockers and resume steps](docs/8002KEYS_STATUS.md).

## Privacy
This baseline will retain upstream's offline posture and no INTERNET permission.
Cloud AI, if added later, must be optional and consent-based. Never commit
credentials, signing keys, keystroke logs or personal dictionaries.
