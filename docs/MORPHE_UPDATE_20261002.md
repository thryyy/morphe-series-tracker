# Morphe update and Series improvements — 2 October 2026

The fork and PR #3114 include Morphe **1.45.0** and subsequent `dev` commits
through `501e66ec5d941f9d688481992621d291f7bf66f0`. Their 67 production Java,
Kotlin and resource files match. The PR retains upstream build files and
workflows; the fork retains its Series release sequence, regression suite and
public-source build tooling.

The update includes focused Continue with a 2.5-second wait, Series-ordered native
Next/Previous/autoplay, Undo for watched edits/Start here/removal, inline recovery
when the saved episode changes, and backup/restore using Morphe’s shared file
picker. Manual watched choices remain independent of native resume positions.
The percentage completion rule also requires at most 90 seconds remaining.
Restoring a backup adds missing series and preserves existing progress and consent.

Morphe moved its main-activity Back fingerprint into `misc.backgesture`; Series
now imports that declaration. The conditional hook still consumes Back while the
Series page handles it. Upstream settings, translations, player helpers and all
other source changes are preserved. Declared Patcher, patch-library and plugin
versions remain 1.14.0, 1.8.0-dev.1 and 1.3.4 respectively.

## Validation

- PR, fork and exported integration Android builds pass.
- All 169 Series extension tests and two Series resource tests pass without skips.
- Three upstream Jam tests pass; five APK-dependent tests skip because no YouTube
  Music APK was supplied. All 88,321 strings validate.
- Native History fingerprint checks pass on original and renamed bytecode for
  21.13.164, 21.16.256 and 21.38.123, including invalid-contract rejection.
- The PR bundle applies all 91 selected patches and rebuilds on 21.13.164 and
  21.16.256. The fork’s own bundle passes the same 91-patch run on 21.16.256.
  APK inspection confirms the native navigation, shared file-picker calls,
  playback, privacy, History and player-control hooks, with no duplicate classes.
- Release metadata tests, npm lockfile installation, source patch round-trip,
  production-file equality and PR build/workflow equality with upstream pass.

Morphe now lists 21.38.130 and 21.39.522 instead of the legacy experimental
21.38.123 APK available here. Normal patching of 21.38.123 applies Series and its
dependencies and passes APK inspection. An additional full-bundle run with the
version check explicitly disabled also applies all 91 selected patches and rebuilds
successfully. This is a development compatibility check, not a change to upstream
target declarations. These results do not validate the newer
experimental APKs, which remain outside Series compatibility.

The preceding signed-out emulator checks verified backup export/import and merge
preview, watched/removal Undo, saved positions after restart/update and missing-
episode recovery. Backup, recovery and Undo were visually checked at 150% text
size. Those checks predate this upstream merge. No physical-device validation of
fresh cross-device progress or native Next/Previous/autoplay is claimed.

Exact automated results and artifact hashes are in
[`config/morphe-update-20261002-evidence.json`](../config/morphe-update-20261002-evidence.json).
