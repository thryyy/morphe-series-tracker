const { test } = require("node:test");
const assert = require("node:assert/strict");
const { metadata } = require("./bundle-metadata.cjs");

for (const version of ["0.1.0", "0.2.0-dev.1"]) {
  test(`Morphe metadata resolves the ${version} release asset`, () => {
    const notes = 'Quotes " and $() stay literal.\nSecond line.';
    const result = metadata(
      { version, gitTag: `series-v${version}`, notes },
      "thryyy/morphe-series-tracker",
      new Date("2026-09-21T10:00:00Z"),
    );
    const json = JSON.parse(JSON.stringify(result));
    assert.equal(json.description, notes);
    assert.equal(json.created_at, "2026-09-21T10:00:00");
    const url = new URL(json.download_url);
    assert.equal(url.hostname, "github.com");
    assert.equal(url.pathname, `/thryyy/morphe-series-tracker/releases/download/series-v${version}/patches-${version}.mpp`);
    assert.equal(json.signature_download_url, "");
  });
}

test("rejects another owner or a mismatched release tag", () => {
  const release = { version: "0.1.0", gitTag: "series-v0.1.0", notes: "" };
  assert.throws(() => metadata(release, "someone/else"));
  assert.throws(() => metadata({ ...release, gitTag: "v0.1.0" }, "thryyy/morphe-series-tracker"));
});
