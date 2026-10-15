# Car++

**A car-side receiver for ICCOA CarLink.** Xiaomi CarWith, OPPO Car+ 车联 and vivo Jovi InCar
phones cast to the head unit over the air — no dongle, no cable, no root, nothing to install on
the phone.

> Notice and licensing: see [`NOTICE.md`](NOTICE.md).

Car++ runs on the head unit, advertises itself as an ICCOA CarLink-capable car, and shows a
6-digit pairing code the phone confirms. The phone keeps using its own maps, music and phone apps.

---

## Status

| Capability | Status | Notes |
|---|---|---|
| Wireless ICCOA CarLink session | Verified on-device | Phone stays in your pocket |
| Pairing code + 30 s re-broadcast watchdog | Verified on-device | A dismissed phone dialog comes back on its own |
| Mirroring | Verified on-device | The phone screen fills the head unit |
| **Touch back-channel (UIBC)** | Verified on-device | Tap / swipe on the head unit to drive the phone; coordinates map from the projection view's real pixel size |
| Foreground keep-alive | Verified on-device | `CarppService` posts a persistent notification so the session survives backgrounding / screen-off |
| Settings (connection / display / video / audio / input / backup / reset / diagnostics) | Implemented | |

> This app speaks ICCOA CarLink only. The Android Auto (AAP) stack and the BYD cluster / HUD
> output were removed wholesale in 0.2.0 and are no longer shipped.

---

## Project layout (SDK-ified)

| Module | Type | Role |
|---|---|---|
| `:sdk-api` | library | **Contract**: `PhoneLink`, `LinkState`, `NavInfo`, `PhoneLinkRegistry`. Interfaces and data classes only |
| `:carpp-sdk` | library | **The SDK**: link contract consumers, settings, diagnostics, foreground keep-alive service, built-in UI (no protocol implementation of its own) |
| `:sdk-iccoa` | library | **ICCOA CarLink protocol plugin** — just one `PhoneLink` implementation, replaceable wholesale |
| `:app` | application | **Thin shell**: applicationId, signing, output naming |
| `:contract` | library | Outbound Intent constants (`HeadUnitIntent`) |

Dependencies always point one way: `app → sdk-iccoa → carpp-sdk → sdk-api`.
**The SDK never depends on the protocol plugin**: the host app calls
`PhoneLinkRegistry.install(IccoaProvider())` once in `Application.onCreate`, and swapping the
protocol is a matter of supplying a different `PhoneLinkProvider`.

See [`carpp-sdk/README.md`](carpp-sdk/README.md) and [`sdk-iccoa/README.md`](sdk-iccoa/README.md).

---

## Build

```bash
./gradlew :app:assembleGithubDebug          # installable APK (works without the ICCOA dex, just cannot connect)
./gradlew :app:assembleGithubRelease        # release APK
./gradlew :carpp-sdk:assembleGithubRelease  # SDK AAR
```

Requires JDK 17, Android SDK (compileSdk 36) and NDK 29.

### Artifacts

| Artifact | Path |
|---|---|
| SDK (AAR) | `carpp-sdk/build/outputs/aar/carpp-sdk-github-release.aar` |
| Contract layer (AAR) | `sdk-api/build/outputs/aar/sdk-api-release.aar` |
| ICCOA plugin (AAR) | `sdk-iccoa/build/outputs/aar/sdk-iccoa-github-release.aar` |
| Debug APK | `app/build/outputs/apk/github/debug/com.carpp.app.debug_0.1.1-debug_debug.apk` |

---

## The ICCOA protocol itself

The car-side ICCOA CarLink implementation is a **third-party proprietary SDK**
(`icar-sdk.jar`, ~5.3 MB) that is **not distributed here** and is not covered by this project's
licence:

- Wired in as a plain jar dependency: `implementation(files("libs/icar-sdk.jar"))`, so AGP handles
  d8 / multidex / alignment / signing normally.
- Drop the jar at `app/libs/icar-sdk.jar` for local builds (that path is git-ignored).

**Without the jar everything still compiles, installs and runs — it just cannot connect to a phone.**

Porting the ICCOA SDK means moving three things together: the jar, the four
`com.share.connect.*` services plus permissions in the manifest, and `androidx.room:room-runtime`.
See [`sdk-iccoa/README.md`](sdk-iccoa/README.md) and [`app/libs/README.md`](app/libs/README.md).

---

## Licence

Released under **AGPL-3.0** ([`LICENSE`](LICENSE)). It incorporates inherited GPLv3 code whose
original author's copyright notice is kept in
[`COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt`](COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt).
Redistribution and derivative works built on this SDK must retain those notices.

An independent community project. Not affiliated with, authorised by or endorsed by Xiaomi,
OPPO, vivo, ICCOA, Google or BYD. Product and company names are trademarks of their respective owners.
