const { writeFile, mkdir, copyFile } = require("node:fs/promises");

function metadata(nextRelease, repository, createdAt = new Date()) {
  if (repository !== "thryyy/morphe-series-tracker") {
    throw new Error("Releases belong to thryyy/morphe-series-tracker");
  }
  const version = nextRelease.version;
  if (!/^\d+\.\d+\.\d+(?:-dev\.\d+)?$/.test(version)) {
    throw new Error(`Unexpected release version: ${version}`);
  }
  if (nextRelease.gitTag !== `series-v${version}`) {
    throw new Error("Release tag does not match the bundle version");
  }
  return {
    created_at: createdAt.toISOString().slice(0, 19),
    description: nextRelease.notes,
    download_url: `https://github.com/${repository}/releases/download/${nextRelease.gitTag}/patches-${version}.mpp`,
    signature_download_url: "",
    version,
  };
}

exports.metadata = metadata;
exports.prepare = async (_config, { nextRelease }) => {
  const value = metadata(nextRelease, process.env.GITHUB_REPOSITORY);
  await writeFile("patches-bundle.json", JSON.stringify(value, null, 2) + "\n");
};

exports.publish = async (_config, { nextRelease }) => {
  metadata(nextRelease, process.env.GITHUB_REPOSITORY);
  const file = `patches-${nextRelease.version}.mpp`;
  await mkdir(".local/release-assets", { recursive: true });
  await copyFile(`patches/build/libs/${file}`, `.local/release-assets/${file}`);
};
