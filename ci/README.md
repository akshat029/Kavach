# Staged CI workflows

The two files in `ci/workflows/` belong at `.github/workflows/`. They are parked
here because the API token used to populate this repository can write every path
**except** `.github/workflows/`, which GitHub gates behind a separate
**Workflows** permission on fine-grained personal access tokens.

They are byte-for-byte the intended final files. Nothing needs to be edited.

## Moving them into place

For each of the two files, in the GitHub web UI:

1. Open the file, for example `ci/workflows/build.yml`.
2. Click the pencil icon to edit it.
3. Click into the **filename box** at the top and replace the whole path with
   `.github/workflows/build.yml`. Typing `/` creates the directories.
4. **Commit changes.**

Repeat for `ci/workflows/update-blocklists.yml` &rarr;
`.github/workflows/update-blocklists.yml`.

The web editor uses your browser session rather than the API token, so it is not
subject to the restriction above.

## Afterwards

Delete this `ci/` directory. It has no role in the build.

Then go to the **Actions** tab, pick **Build**, and click **Run workflow** to
produce the first APK. The debug APK appears as the `kavach-debug-apk` artifact
at the bottom of the run summary.

## What the two workflows do

| File | Trigger | Purpose |
| --- | --- | --- |
| `build.yml` | push and PR on `main`, plus manual | Runs unit tests, runs Android Lint, assembles the debug and release APKs, publishes checksums and artifacts |
| `update-blocklists.yml` | 03:00 UTC nightly, on seed edits, plus manual | Recompiles the tracker and ad lists from upstream feeds, syncs the copies bundled into the APK, and commits only when the output changed |

`build.yml` regenerates `gradle-wrapper.jar` from the pinned Gradle 8.9
distribution on every run, which is why no binary is committed to this
repository. Release signing activates only when the `KAVACH_KEYSTORE_BASE64`
secret exists; without it the release APK is unsigned and you should install the
debug one.
