# app/libs — runtime dependencies that are not in git

## `icar-sdk.dex` — ICCOA CarLink protocol SDK

The ICCOA CarLink protocol is the phone-side transport behind Xiaomi CarWith, OPPO Car+ 车联 and
vivo Jovi InCar. **This file is deliberately absent from the repository.** It is a prebuilt,
third-party, proprietary bytecode blob, so it is neither redistributed here nor covered by this
project's AGPL-3.0 licence. `.gitignore` keeps it out (`*.dex`), and
[`injectIccoaDex`](../build.gradle.kts) treats its absence as "build without CarWith support"
rather than as an error.

### Why it needs a build step at all

Gradle/AGP does not consume `.dex` files as dependencies — neither `implementation` nor
`runtimeOnly` will put one into the APK. So the SDK is split in two:

| Half | File | How it is used |
| --- | --- | --- |
| Compile time | [`app/stubs/icar-sdk-stubs.jar`](../stubs) | **Committed.** Signature-only stubs; `compileOnly`, so it never enters the APK. |
| Run time | `app/libs/icar-sdk.dex` | Injected into the packaged APK as the next free `classesN.dex`, then the APK is realigned and re-signed. |

The build then loads every `classesN.dex` through the normal `BaseDexClassLoader` path, so the
SDK classes simply exist at runtime.

### Providing it

Put a file named `icar-sdk.dex` in this directory and build normally. The expected content is
the CarLink SDK dex (currently ~7.1 MB) that implements the `com.share.connect.*` service
surface declared in `app/src/main/AndroidManifest.xml`.

**CI builds without it.** A repository secret is not an option — GitHub caps Actions secrets
at 48 KB and this dex is ~7 MB — so `.github/workflows/android-ci.yml` is a compile / R8 /
unit-test check whose artifact has no CarWith support. Release assets are built locally with
this file in place. On a self-hosted runner that already has the file, CI picks it up and the
artifact is functional.

### Do not move the SDK to another package

The SDK is used by cross-process component names and by package names baked into its own
bytecode. Renaming or relocating any of it — including the `com.share.connect`,
`com.easy`, `com.ucar` and `com.ucarsink` package roots — silently breaks the session with
no error message. See [docs/ICCOA_SDK_INTEGRATION.md](../../docs/ICCOA_SDK_INTEGRATION.md)
for the three things that must be ported together with the dex (manifest services, manifest
permissions, and the `androidx.room` runtime dependency).
