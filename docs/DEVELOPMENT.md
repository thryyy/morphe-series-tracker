# Development

`codex/series-tracking-pr` contains only the Series code and resources for an upstream PR against Morphe’s `dev` branch. Keep the upstream build files and workflows unchanged.

`main` maintains this patch source, releases and the regression suite. Keep tests and APK verification tools here; they are not part of the upstream submission. Apply feature fixes to both branches and record build and device results in the PR. The older `codex/series-tracking-dev` branch is retained for release history.

The build uses checksum-pinned public sources for Morphe’s Gradle plugin and patcher, plus published libraries. `scripts/bootstrap.py` downloads them into ignored `.local/`. Transitive dependencies still come from the upstream repositories. No personal access token is needed for local builds.

Set `ANDROID_HOME` or an ignored `local.properties` with `sdk.dir`. Use JDK 21 and install Android SDK platform/build tools 36.

```sh
./scripts/build.sh
./scripts/build.sh :patches:verifySeriesHost -PseriesHostApk=/path/to/original.apk
./scripts/build.sh :patches:verifySeriesApk -PseriesApk=/path/to/patched.apk
```

Update `config/toolchain-lock.json` when changing build inputs; record the source revision and verify each download’s SHA-256. Check supported YouTube targets against real APKs before adding them.

## Upstream base

The release source includes Morphe **1.44.0 stable** (`92dd0ef86`).
Merge stable upstream tags into `main`, preserving the Series release metadata and
version sequence. The upstream PR follows `upstream/dev` independently.

## Releases

The release workflow follows Morphe’s semantic-release setup. Work on `dev` and dispatch the Release workflow there for preview releases. Merging into `main` publishes stable releases automatically. `feat:` creates a minor release, `fix:` a patch release. Tags use the separate `series-v` prefix.

Before the first release, create the annotated `series-v0.0.0` baseline tag at the upstream base. This keeps the first Series changelog from including Morphe’s entire history. Push only the intended branches and this tag; never mirror the private development repository.

The workflow builds and tests before publishing. It generates `patches-bundle.json`, `patches-list.json`, `PATCHES.md` and `CHANGELOG.md`, and uploads the `.mpp` bundle. Keep the README hand-written. Don’t upload APKs, signing keys, device logs or local test recordings.

GitHub Actions needs permission to write repository contents for release commits and assets. It uses the repository’s `GITHUB_TOKEN`, not a personal token. The workflow does not notify or deploy anything to Morphe’s infrastructure.

After a release, add the GitHub repository as a source in Morphe and verify its name, version, patch list and download. Future releases update that source. Use this bundle as the YouTube source; it already includes the official patches.
