# Validation

The Series feature was tested on a Pixel 10 Pro running Android 17 with YouTube 21.13.164, 21.16.256 and experimental 21.38.123.

The checks covered History/Series navigation, retained playlists and positions, episode lists, resume playback, the player shortcut, manual watched/unwatched state, settings and persistence after restart. YouTube was set to English while the phone system language was French.

The redundant settings shortcut was removed and Series Tracker was moved into General. These settings changes were rechecked on 21.16.256 after the other device tests.

169 extension tests and 2 resource tests cover storage, progress, privacy rules, request ownership and UI state. APK checks verify the injected playback, account and History bridges. Native History matching is also checked against renamed bytecode and rejected when required members are missing or ambiguous.

21.38.123 remains experimental. This is not exhaustive device coverage: real cross-device synchronization, account/incognito transitions, long background playback and fresh-install onboarding still need dedicated testing.

Current upstream base: Morphe **1.45.0** plus subsequent development commits,
through `501e66ec5` (2 October 2026). Release builds must pass the same automated checks; compatibility is limited to explicitly declared targets.

## Morphe 1.44.0 update — 22 September 2026

The stable merge passes the Android bundle build, string validation and all 149
regression tests. Full patching of YouTube 21.16.256 succeeds for both the stable
source and the updated upstream PR branch (based on dev `b20648b12`). The player
shortcut uses the upper control row in both player styles, with a stacked-episodes
icon and the existing Series settings toggle.

No new physical-device test was performed for this merge or shortcut relocation;
the device coverage above describes the earlier implementation.

## Morphe preview update — 30 September 2026

The fork and PR #3114 share identical production Java, Kotlin and resources on
upstream `444bb0dc1`. The merge preserves both Series translations and upstream's
new player-icon strings. Patch/extension library pins are now 1.8.0-dev.1;
Patcher remains 1.14.0. Protobuf follows upstream 4.36.2.

The fingerprint refactor requested during upstream review is now also in this
fork. The host verifier runs fingerprints inside a real Patcher context and
compares them with the previous matcher on original and renamed bytecode. It
rejects ambiguous factories/dispatch overloads, incorrect allocation types,
mismatched null-argument registers and missing required contracts.

The bundle build and 84,179-string validation pass. All 149 Series tests pass
without skips; four upstream Jam tests pass, while five APK-dependent Jam tests
are skipped because no YouTube Music APK was provided. Release metadata tests
pass. Full PR-bundle patching applies 90 patches successfully on YouTube
21.13.164, 21.16.256 and experimental 21.38.123. Inspection checks unique DEX
classes, playback/privacy/History wiring and the shared player top-control hook.

The fork bundle also passes full 90-patch rebuilding and APK inspection on
21.16.256. Exact source/artifact hashes are recorded in
[`config/morphe-update-20260930-evidence.json`](../config/morphe-update-20260930-evidence.json).

No physical-device installation or new cross-device/account/incognito check was
performed for this update. Upstream now offers additional experimental YouTube
targets, but Series support remains limited to the three verified versions.

## Morphe update and Series improvements — 2 October 2026

See [the current validation report](MORPHE_UPDATE_20261002.md) for the latest
upstream base, backup/Undo/recovery changes, automated checks and device limits.
