# Development

`codex/series-tracking-dev` contains the Series feature on Morphe’s `dev` branch, without distribution changes. Keep fixes suitable for an upstream PR there, then merge them into the distribution branch.

The build uses checksum-pinned public sources for Morphe’s Gradle plugin and patcher, plus published libraries. `scripts/bootstrap.py` downloads them into ignored `.local/`. Transitive dependencies still come from the upstream repositories. No personal access token is needed for local builds.

Set `ANDROID_HOME` or an ignored `local.properties` with `sdk.dir`. Use JDK 21 and install Android SDK platform/build tools 36.

```sh
./scripts/build.sh
./scripts/build.sh :patches:verifySeriesHost -PseriesHostApk=/path/to/original.apk
./scripts/build.sh :patches:verifySeriesApk -PseriesApk=/path/to/patched.apk
```

Update `config/toolchain-lock.json` when changing build inputs; record the source revision and verify each download’s SHA-256. Check supported YouTube targets against real APKs before adding them.

## Releases

The release workflow follows Morphe’s semantic-release setup. Work on `dev` and dispatch the Release workflow there for preview releases. Merging into `main` publishes stable releases automatically. `feat:` creates a minor release, `fix:` a patch release. Tags use the separate `series-v` prefix.

Before the first release, create the annotated `series-v0.0.0` baseline tag at the upstream base. This keeps the first Series changelog from including Morphe’s entire history. Push only the intended branches and this tag; never mirror the private development repository.

The workflow builds and tests before publishing. It generates `patches-bundle.json`, `patches-list.json`, `PATCHES.md` and `CHANGELOG.md`, and uploads the `.mpp` bundle. Keep the README hand-written. Don’t upload APKs, signing keys, device logs or local test recordings.

GitHub Actions needs permission to write repository contents for release commits and assets. It uses the repository’s `GITHUB_TOKEN`, not a personal token. The workflow does not notify or deploy anything to Morphe’s infrastructure.

After a release, add the GitHub repository as a source in Morphe and verify its name, version, patch list and download. Future releases update that source. Use this bundle as the YouTube source; it already includes the official patches.
