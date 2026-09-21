# Validation

The Series feature was tested on a Pixel 10 Pro running Android 17 with YouTube 21.13.164, 21.16.256 and experimental 21.38.123.

The checks covered History/Series navigation, retained playlists and positions, episode lists, resume playback, the player shortcut, manual watched/unwatched state, settings and persistence after restart. YouTube was set to English while the phone system language was French.

The redundant settings shortcut was removed and Series Tracker was moved into General. These settings changes were rechecked on 21.16.256 after the other device tests.

147 extension tests and 2 resource tests cover storage, progress, privacy rules, request ownership and UI state. APK checks verify the injected playback, account and History bridges. Native History matching is also checked against renamed bytecode and rejected when required members are missing or ambiguous.

21.38.123 remains experimental. This is not exhaustive device coverage: real cross-device synchronization, account/incognito transitions, long background playback and fresh-install onboarding still need dedicated testing.

Upstream base for this distribution: `9523afa92` (21 September 2026). Release builds must pass the same automated checks; compatibility is limited to explicitly declared targets.
