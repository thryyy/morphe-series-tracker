# Series Tracker

Follow YouTube playlists as series. Keep your place, see new episodes and resume where you left off.

This is an unofficial Morphe patch source based on **Morphe 1.45.0 plus subsequent dev changes through `501e66ec5`**. It includes Morphe’s patches plus **Series tracking**, so you can apply both in one run.

## Install

[Add to Morphe](https://morphe.software/add-source?github=thryyy%2Fmorphe-series-tracker).
Choose this source for YouTube and keep **Series tracking** selected. Use an original YouTube APK; don’t combine this bundle with the official source in the same patch run.

Full bundle patching is verified with **21.13.164** and **21.16.256**. Series also retains experimental **21.38.123**, which newer upstream patches no longer select by default. See [the current validation report](docs/MORPHE_UPDATE_20261002.md). Device testing predates this upstream update; newer experimental APKs are not yet declared compatible with Series tracking.

## Use

Open **History → Series**. Follow a playlist with **+** or the player’s Series button. Tap a thumbnail to continue; tap the title to see its episodes.

Recording is off by default. Enable it under **Morphe → General → Series Tracker**. **Use YouTube progress** can pick up progress from another device; your followed-series list stays on this phone.

When playback starts from Series, the native Next/Previous controls and autoplay follow its episode order. Undo reverses watched edits, Start here and removal. If a playlist changes, Series helps you choose where to continue.

**Backup and restore** in Series Tracker settings uses Morphe’s file picker. Restore adds missing series and keeps existing progress. Backups do not grant recording or account permissions.

The local library is shared across YouTube accounts. YouTube’s pause/clear-history controls do not clear it. Use **Series → More → Clear all local progress**.

## Build

Requires JDK 21, Python 3 and Android SDK 36. Run `./scripts/build.sh`.
The `.mpp` bundle is written to `patches/build/libs/`.

See [development and releases](docs/DEVELOPMENT.md) and [validation](docs/VALIDATION.md).
Based on [Morphe Patches](https://github.com/MorpheApp/morphe-patches), under [GPLv3](LICENSE) and the additional terms in [NOTICE](NOTICE).
