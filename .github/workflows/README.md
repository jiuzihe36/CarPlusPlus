# Workflows

| Workflow | Trigger | What it does |
| --- | --- | --- |
| [`android-ci.yml`](android-ci.yml) | push to `main`, any pull request, manual | Compiles the `github` flavor (debug and release) on JDK 17 with pinned NDK/CMake, checks translation consistency, runs the JVM unit tests, and uploads the APKs and the test report as artifacts. |
| [`pages.yml`](pages.yml) | push to `main` touching `site/**`, `scripts/build_site.py` or itself; manual | Regenerates `site/` from `site/content.json` and deploys it to GitHub Pages. |

## Secrets

None. The CarWith / CarLink SDK dex that CI would otherwise need cannot be carried as a
repository secret: Actions secrets are capped at **48 KB** and the dex is about 7 MB.

CI therefore builds and tests **without** the SDK. `injectIccoaDex` is skipped, and the
uploaded APK cannot connect to CarWith. That is a compile / R8 / unit-test check, not a
release candidate — the release assets on the releases page are built locally, where
`app/libs/icar-sdk.dex` is present.

If a runner already has `app/libs/icar-sdk.dex` on disk (a self-hosted runner, or a step
that provisions it), the build picks it up automatically and the artifact is functional;
the workflow logs which of the two paths it took.

## Notes

- CI does **not** sign release APKs. `key.properties` and the keystore are untracked by design,
  so the `githubRelease` artifact is an unsigned R8/shrinking check rather than a distributable.
  Release assets on the releases page are built and signed locally.
- Only the `github` flavor is built. The `playstore` flavor is not covered here.
