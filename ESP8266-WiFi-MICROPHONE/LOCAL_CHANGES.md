# Local changes to this firmware

Made for use with the Tone Player Android app (this clone sits in the `esp-tone` work folder). Upstream:
github.com/Dzantemir/ESP8266-WiFi-MICROPHONE at commit 7ff3078; `git diff` shows our source changes.

Build with `tools/build_mic.sh` (macOS/Linux, ESP8266_RTOS_SDK v3.4); images go to `build-esp01/`.
Flash with `tools/flash_mic.sh`:

- **Update** (keeps Wi-Fi and all settings): only the app, `esp-mic-app.bin`, at **0x10000**. This is the default.
- **Blank chip** (`--full`): `esp-mic-merged.bin` at 0x0 (DOUT, 1 MB). This **erases the settings**: the merged image
  fills 0x0–0xFFFF, which covers the settings (NVS) partition at 0x9000–0xEFFF with blank bytes.

Current `build-esp01/` images contain every change below (built 2026-11-03 11:47).

| Change | Where | Why |
|---|---|---|
| Wi-Fi power save off (`CONFIG_STREAMER_WIFI_PS_NONE`) | sdkconfig | Modem sleep could not carry PCM streams: `sendto` failed with ENOMEM |
| Wi-Fi TX packets 4 → 10 (`CONFIG_ESP8266_WIFI_TX_PKT_NUM`) | sdkconfig | Rides out short airtime dips |
| On ENOMEM, wait one tick and retry the send (twice) instead of dropping the frame and sleeping 50 ms | `main/udp_stream.c` | Each failure cost about 5 frames |
| Scan all channels and join the strongest AP with the SSID (was: first found from channel 1) | `main/wifi_sta.c` | In a building with many APs it joined one at −74 dBm; it now picks −49 dBm. Logs the AP, channel and RSSI after GOT_IP |
| Link check: ping the gateway every 5 s; after 3 unanswered in a row, leave and rejoin Wi-Fi. Armed only once the gateway has answered on this connection. Ignored while streaming: at ~1 Mbit/s the pings themselves get lost, which once cut a healthy stream | `main/wifi_sta.c` | The AP sometimes dropped the station while it still reported "connected" (−47 dBm). It kept hearing broadcasts, but nothing it sent got through, so it was unreachable until rebooted |
| AT commands over the network: EASSP cmd `0x04` (payload: one AT line) → `0x84` (payload: the text the UART would print, up to 1400 bytes) | `main/at_cmd.c`, `main/svc_port.c`, `include/svc_protocol.h`, `include/at_cmd.h`, `include/svc_port.h` | Configure from the phone. The AT task runs the line between UART reads. A resend with the same sequence number gets the stored answer and does not run the command again |

The network AT command is not authenticated, the same as the rest of EASSP. Anyone on the LAN can change
settings, including the Wi-Fi network (the password travels in clear text over UDP).
