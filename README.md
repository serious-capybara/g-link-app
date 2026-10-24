# G-Link for Android

G-Link is a fork of [KDE Connect Android](https://invent.kde.org/network/kdeconnect-android), built to pair
with the **G-Link GNOME extension** on Fedora/Wayland desktops.

> **Lineage & license.** Derived from KDE Connect and distributed under the GNU GPL (v2-only / v3, see `COPYING`
> and `LICENSES/`). Original copyright and SPDX notices are preserved.

Source: https://github.com/serious-capybara/g-link-app  ·  GNOME extension: https://github.com/serious-capybara/g-link-extension

## Focus areas

| Area | Goal | Status |
| --- | --- | --- |
| Discovery | LocalSend-style instant discovery: mDNS (`_glink._udp`, NsdManager) plus a 3-packet UDP broadcast burst, deferred network events, re-scan when the Wi-Fi IP changes | implemented (measure with the `GLink/Discovery` log) |
| Reconnection | Foreground service + network callbacks, plus a WorkManager fallback (`ReconnectWorker`, every ~15 min on Wi-Fi) | implemented |
| File transfer | 256 KiB copy buffers, size-based socket buffers (1-4 MiB for big files), TCP_NODELAY on the control link, live MB/s in the notification and log | implemented |
| Remote screen | View-only desktop screen via GNOME Remote Desktop: `RdpPlugin` + `ScreenShareActivity` (opens an installed RDP app via `rdp://`; an embedded renderer plugs in through `RdpEngine`) | request/response flow implemented, embedded renderer not bundled |

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
