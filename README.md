# G-Link for Android

G-Link is a fork of [KDE Connect Android](https://invent.kde.org/network/kdeconnect-android), built to pair
with the **G-Link GNOME extension** on Fedora/Wayland desktops.

> **Lineage & license.** Derived from KDE Connect and distributed under the GNU GPL (v2-only / v3, see `COPYING`
> and `LICENSES/`). Original copyright and SPDX notices are preserved.

## Focus areas

| Area | Goal | Status |
|---|---|---|
| Discovery | LocalSend-style instant discovery: mDNS (`_glink._udp`) plus tuned UDP broadcast, no manual refresh | mDNS service type present; tuning in progress |
| Reconnection | Seamless background reconnect after sleep, Wi-Fi roaming and reboot | planned |
| File transfer | High-throughput large-file transfer over local Wi-Fi | planned |
| Remote screen | Built-in RDP client to view a Fedora Wayland desktop (served by GNOME Remote Desktop) | planned |

## Build

```bash
./gradlew clean assembleDebug         # build/outputs/apk/debug
./gradlew testDebugUnitTest lintDebug
```
Requires JDK 17+ and the Android SDK (`sdk.dir` in `local.properties`, which is not committed).

## Identifiers
- Application ID / namespace: `com.glink.app`
- Wire protocol namespace: `glink.*` (e.g. `glink.ping`, `glink.pair`), protocol version 8
- Must match the desktop side: see `stage3_protocol_sync.py`
