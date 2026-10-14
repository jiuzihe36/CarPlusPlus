#!/usr/bin/env bash
# Install a release or test APK on the car over ADB and pre-grant the runtime permissions
# Car++ needs, without relying on debuggable/run-as access.
set -euo pipefail
CAR="${1:?Usage: install.sh CAR_IP:5555 PATH_TO_APK}"
APK="${2:?Provide an APK path}"
ADB="${ADB:-adb}"
# The release build. A debug build installs as com.miauto.app.debug — override with PKG=...
PKG="${PKG:-com.miauto.app}"
[[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }
"$ADB" connect "$CAR"
adb_car() { "$ADB" -s "$CAR" "$@"; }
adb_car get-state
adb_car install -r "$APK"
adb_car shell am force-stop "$PKG"
if adb_car shell pidof "$PKG" >/dev/null; then
    echo "Car++ is still running; stop it before continuing." >&2
    exit 1
fi
# Car++ starts the CarWith SDK from onCreate and from onResume. Advertising silently fails
# when the Bluetooth runtime permissions were never granted, so grant them up front.
for permission in BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE BLUETOOTH_SCAN \
    NEARBY_WIFI_DEVICES ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION \
    RECORD_AUDIO READ_PHONE_STATE POST_NOTIFICATIONS; do
    adb_car shell pm grant "$PKG" "android.permission.$permission" 2>/dev/null || true
done
adb_car shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb_car shell settings put secure location_mode 3
adb_car shell dumpsys deviceidle whitelist +"$PKG"
adb_car shell appops set "$PKG" RUN_IN_BACKGROUND allow
adb_car shell appops set "$PKG" RUN_ANY_IN_BACKGROUND allow
adb_car shell am start -n "$PKG/com.andrerinas.openheadunit.main.MainActivity"
echo 'The car is now advertising. Open CarWith on the phone and pick this car from the'
echo 'nearby-device list; the 6-digit pairing code is on the car screen.'
echo 'While unconnected the car re-advertises every 30 seconds, so a dismissed prompt returns.'
