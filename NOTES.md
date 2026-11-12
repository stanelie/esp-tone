# esp-tone: working notes and findings

Everything learned while building the ESP8266 microphone setup (2026-11-02 to 2026-11-03), so it does not have to
be rediscovered. `README.md` is the user guide; `ESP8266-WiFi-MICROPHONE/LOCAL_CHANGES.md` lists the firmware changes.

## Where things are

| Path | What |
|---|---|
| `firmware/test_tone`, `firmware/mic_stream` | Our Arduino sketches (HTTP/WAV on port 1234): 440 Hz tone, and the INMP441 mic |
| `ESP8266-WiFi-MICROPHONE/` | Clone of Dzantemir's EASSP microphone firmware (ESP8266_RTOS_SDK), with our changes (`git diff`) |
| `ESP8266-WiFi-MICROPHONE/build-esp01/` | Current images: `esp-mic-app.bin` (update at 0x10000), `esp-mic-merged.bin` (blank chip at 0x0) |
| `android/` | Tone Player app, package `app.toneplayer`. Plays both protocols |
| `pc-player/tone_player.py` | Finds a tone transmitter and opens it in VLC |
| `tools/` | Build/flash scripts, simulators, analysis tools (below) |

The mic currently runs **the EASSP firmware** (hostname `esp-streamer`, MAC 8C:AA:B5:xx:xx:xx, usually
10.8.1.168 on `your-wifi`). Our own `mic_stream` sketch also works and is the simpler fallback.

## Hardware

- **Board:** Ai-Thinker ESP-01 (ESP8266EX, 1 MB flash). Only GPIO0, GPIO2, TX and RX reach the header.
- **I2S microphone input needs three chip pins that are not on the header**, soldered directly to the chip:

  | INMP441 | ESP8266 | Chip pin |
  |---|---|---|
  | SD (data) | GPIO12 (MTDI) | 10 |
  | SCK (bit clock) | GPIO13 (MTCK) | 12 |
  | WS (word select) | GPIO14 (MTMS) | 9 (a corner pin) |

  These pins are free on the ESP-01. The flash chip uses chip pins 18–23. The mic's L/R pin chooses the slot:
  GND = left, VDD = right.
- **Programmer:** CH340 USB-serial (VID 1A86, PID 7523) with a home-soldered BOOT switch to GPIO0. Hold BOOT while
  plugging in to enter the bootloader.
- **Flash from the Mac.** On the Windows laptop (ZBOOK), esptool syncs and then times out partway through every write,
  with every driver version tried. Reading the serial log (COM25 there) works fine.
- **INMP441 limits:** about 61 dB SNR, so roughly 10 useful bits, and response to about 15 kHz. 16-bit PCM captures
  everything it delivers; 24-bit only adds data. The user hears a clear difference going to 44.1 kHz, so 44.1 kHz
  16-bit PCM is the preferred setting.
- **Real sample rates:** the ESP derives the I2S clock from 160 MHz through two integer dividers, so the nominal rate
  is never exact. The app plays at the real rate (`Eassp.actualRate`), so pitch is right:

  | Nominal | 16-bit | 24-bit |
  |---|---|---|
  | 16000 | – | 16026 (our sketch) |
  | 44100 | 43860 | 43860 |
  | 48000 | 48077 | 48309 |

## Our mic sketch (`firmware/mic_stream`)

- **Crackle was lost samples, not the mic link.** The ESP8266 Arduino core's I2S driver has only 8 DMA buffers × 64
  words (14 ms). Wi-Fi stalls overran it, losing 1.4% of samples. The sketch now carries its own copy of the driver
  with 32 buffers (`mic_i2s.cpp`, functions renamed `mi2s_*`). Measured afterwards: 16,027 samples/s, no gaps.
- Uses 24-bit mode (48 SCK per frame). The INMP441 datasheet asks for 64; 48 works in practice.
- `tools/analyze_mic.py host secs out.wav` records the HTTP stream and counts lost samples and isolated spikes.

## EASSP firmware (the one in use)

- **Protocol:** UDP control on port 3950. Header `EA 55 01 cmd seq(LE16) len(LE16)`.
  - Commands: DISCOVER `0x01`, CONFIGURE `0x02` (payload: stream port, big-endian), STOP `0x03`.
    The device answers INFO `0x81` (58 bytes).
  - The device stops streaming after 15 s without a DISCOVER, so the player repeats one every 3 s.
  - Idle devices broadcast INFO every 1–5 s.
- **Audio:** a 16-byte header (seq at 0, codec at 6: 5 = ADPCM / 6 = PCM, rate enum at 7, channels at 8,
  frame_ms at 9, bits at 14), then the samples. Over TCP, each frame is prefixed with a big-endian u16 length.
  Their sequence numbers count only packets actually sent, so a gap in them means network loss.
- **Our addition:** AT commands over the network. Cmd `0x04` carries one AT line; reply `0x84` carries the text the
  UART would print. The app's settings screen is built on it.
- **Building** needs ESP8266_RTOS_SDK **v3.4**: `tools/build_mic.sh` on macOS/Linux; on Windows it was done in WSL
  Ubuntu 24.04 (`tools/wsl_build_mic.sh`).
  - Ubuntu 24.04 fixes needed: symlink `python` → python3; pre-create the SDK's venv with `python3 -m venv`
    (PEP 668 refuses the installer's pip); pin `setuptools<81` (newer setuptools dropped `pkg_resources`, which
    the SDK imports).
  - Source `export.sh` directly, never through a pipe.
- **Flash layout:** bootloader 0x0, partition table 0x8000, **settings (NVS) 0x9000–0xEFFF**, phy_init 0xF000,
  app 0x10000.
  - A merged image covers 0x0–0xFFFF and blanks the settings. That's why Wi-Fi had to be set again after
    every full flash.
  - **Update with the app alone at 0x10000** (`tools/flash_mic.sh`).
  - Set Wi-Fi again with `AT+WIFI=ssid,password` over serial (115200, CR+LF), then `AT+RST`.

### Network problems found, and their fixes (all in `LOCAL_CHANGES.md`)

1. **`sendto failed: errno=12` (ENOMEM) floods.** The shipped config had MAX_MODEM power save, so the radio dozes
   between beacons and can't keep up with 1.15 Mbit/s PCM. Their firmware also had only 4 Wi-Fi TX packets, and
   slept 50 ms after each failure (about 5 frames lost each time).
   - Fixed with power save off, 10 TX packets, and retrying the send instead of dropping.
   - Result: 16,000 packets with 6 drops.
2. **Weak link (−74 dBm), periodic collapse** with `AES PN` errors. The AP's retransmissions show the ESP's uplink
   wasn't getting through.
   - Cause: the firmware used fast scan, which joins the **first** AP with the SSID, from channel 1 up. The
     building has many APs on several channels.
   - Fixed with an all-channel scan that joins the strongest. It now joins channel 11 at −47…−49 dBm.
3. **"Half-connected" ESP:** it reports connected at −47 dBm and still hears broadcasts (it logs the phone's
   DISCOVERs), but nothing it sends arrives: no ping, no replies, no announcements. Only a reboot cleared it.
   The AP had dropped it without the ESP noticing.
   - Fixed with a **link check**: ping the gateway every 5 s; after 3 misses in a row, rejoin Wi-Fi.
   - It arms only after the gateway has answered once on the current connection.
   - It is **ignored while streaming**: the first version rejoined in the middle of a perfect stream, because pings
     get lost under ~1 Mbit/s of audio.
   - A stuck link mid-stream still recovers: the phone's heartbeats stop, their watchdog ends the stream after
     15 s, then the idle check rejoins. About 30 s in all.
   - **Not yet confirmed on a real stuck event.** The fixed build (`esp-mic-app.bin`, 11:47) is not flashed yet.
4. **The Windows PC (10.8.0.240, wired) can't reach the ESP at all** (ping, DISCOVER, AT), while the phone on Wi-Fi
   can. Probably client isolation between wired and wireless or between APs. So test from the phone, or from a
   machine on Wi-Fi.
5. **Wi-Fi settings "hosed" once** after boot-time serial garbage (`EEEE…`), and lost on every full flash. See flash
   layout above.

## Android app (`android/`)

- **Discovery:** a hand-rolled mDNS PTR query (Samsung's NsdManager never reported the ESP), a subnet scan for the
  tone sketch, and an EASSP DISCOVER broadcast.
  - Scans on launch and every 15 s while the screen is shown. Paused while playing, since a scan probes about
    500 addresses. Pull down to rescan.
  - Discovery cannot cross subnets: use **Add IP**.
- **Test phone:** Galaxy S8 (SM-G950W), Android 9, on 10.8.1.194/23. Check `adb shell ip -4 addr show wlan0`.
- **Playback engine** (`JitterPlayer.kt`): a jitter buffer feeding a low-latency AudioTrack in 5 ms blocks.
  - **Latency floor is the network.** Ping from the PC to the phone ranged 8–130 ms during streaming. Android
    output adds 39–49 ms, because the ESP's odd rates (43,860 / 48,309 Hz) keep the track off the fast mixer path.
    Sub-10 ms isn't possible. Auto settles around 120–170 ms total; a fixed 40 ms gave about 75 ms with frequent
    fill-ins.
  - **Buffer setting:** Auto = the worst arrival gap of the last 10 s + 15 ms, 40–250 ms. Or a fixed 10–200 ms.
  - **Lost packets** (sequence gaps) are filled **two-sided** with order-40 linear prediction: forward from the audio
    before the gap, backward from the ~12.5 ms after it, then crossfaded.
    - The first try used pitch-period repetition, as their Windows player's BWSOLA does. A capture showed about
      115 hard clicks in 19 s, because a chord's period (18 ms) is longer than the 14 ms search.
    - LPC brought that to 0 clicks across 170 fills at 3% loss.
  - **Late audio** (buffer dry) is extrapolated one-sided: full level for 10 ms, faded out by 60 ms.
  - **Built-up delay** is removed one pitch period at a time, crossfaded.
  - **Android output underruns** were heard as cutouts that the dropout counter didn't show. The output buffer now
    starts at 40 ms (20 ms underran) and grows by 10 ms if needed; these show as "output glitches".
  - 24-bit PCM is played as 16-bit (top two bytes).
  - **Debug capture:** `adb shell run-as app.toneplayer touch files/capture.raw.request`, play, then
    `adb exec-out run-as app.toneplayer cat files/capture.raw > capture.raw` (20 s as played, 16-bit LE).
    Analyse with `tools/clicks.py capture.raw RATE`.
- **Microphone settings** (long-press an EASSP mic): reads every setting with AT queries.
  - Apply sends only the changes, then `AT+HOTRESTART`; the player follows the new format by itself.
  - A console sends any AT command.
  - Unauthenticated, like the rest of EASSP: anyone on the LAN can change settings, and a Wi-Fi password set from
    the phone crosses the LAN in clear text.
- The app reconnects after interruptions until Stop, resends CONFIGURE until audio arrives, and plays from a
  foreground service with a Wi-Fi lock.

## Tools (`tools/`)

| Tool | Use |
|---|---|
| `build_mic.sh` | Build the EASSP firmware (macOS/Linux) → `build-esp01/esp-mic-app.bin`, `esp-mic-merged.bin` |
| `flash_mic.sh [--full]` | Flash it via `/dev/cu.usbserial-*`: app at 0x10000 (keeps settings) or everything at 0x0 |
| `merge_mic.py` | Make `esp-mic-merged.bin` from the four images |
| `wsl_build_mic.sh`, `wsl_sdk_install.sh` | The Windows/WSL build and SDK install used so far (paths are Windows-specific) |
| `serial_log.py PORT OUT [AT-line]` | Log the ESP's serial output with timestamps, without resetting it |
| `probe.py HOST` | DISCOVER and network `AT+STATUS` to one device, then listen for its broadcasts |
| `listen.py` | List every EASSP device heard on the LAN |
| `mock_eassp.py`, `mock_eassp2.py [rate loss stall_every stall_ms]` | EASSP device simulators streaming a tone or a chord, with packet loss and stalls |
| `mock_at.py` | EASSP simulator that answers network AT commands, for testing the settings screen |
| `analyze_mic.py`, `clicks.py`, `click_detail.py` | Glitch analysis of a recording or an app capture |
| `ui.ps1` | PowerShell helpers to drive the phone's UI over adb (tap/long-press by text, dump texts) |

## Open items

- Flash `esp-mic-app.bin` (link check fixed for streaming) and watch for `link check: gateway answers, armed` after boot.
- See whether the link check recovers a real half-connected episode by itself.
- Why the wired PC can't reach the ESP (item 4 above). It only matters for testing from the PC.
- Their RawTX transport is not playable by the app.

## Moving to the Mac

1. **Copy the whole `esp-tone` folder** to the Mac, e.g. `~/Documents/esp-tone`. It is self-contained.
   Make the scripts executable: `chmod +x tools/*.sh android/gradlew`.
2. **Firmware toolchain**, once:
   ```bash
   mkdir -p ~/esp && cd ~/esp
   git clone -b v3.4 --recursive https://github.com/espressif/ESP8266_RTOS_SDK.git
   cd ESP8266_RTOS_SDK && ./install.sh
   ```
   - If the dependency check fails on `pkg_resources`, run
     `~/.espressif/python_env/rtos3.4_*/bin/pip install "setuptools<81"`.
   - The esp-2020r3 toolchain is an x86-64 build. On an Apple Silicon Mac it needs Rosetta
     (`softwareupdate --install-rosetta`).
   - Then `tools/build_mic.sh` and `tools/flash_mic.sh`.
   - `python3 -m pip install --user esptool` if it isn't installed yet (upgrade pip first).
3. **Android:** install Android Studio (or the command-line SDK) and a JDK 17+.
   - Change `android/local.properties` to `sdk.dir=/Users/<you>/Library/Android/sdk`.
   - Build with `cd android && ./gradlew assembleDebug`.
   - Install with `~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`.
4. **The Claude Code conversation:** the transcript is
   `C:\Users\selie\.claude\projects\C--Users-selie-Documents\1d291832-9e29-4f20-86af-69aacb7c4318.jsonl`.
   - Its earlier part is `efa3aae0-73ae-42b2-957f-2c96491596aa.jsonl`, already summarised inside the newer one.
   - On the Mac, copy it into `~/.claude/projects/<folder>/`, where `<folder>` is the working directory with every
     `/` replaced by `-`. For `/Users/<you>/Documents/esp-tone` that is `-Users-<you>-Documents-esp-tone`.
   - Start `claude --resume` in that folder and pick it.
   - Also copy the memory notes, `C:\Users\selie\.claude\projects\C--Users-selie-Documents\memory\`, into
     `~/.claude/projects/<folder>/memory/`.
   - Paths inside the old conversation are Windows paths. This file and `LOCAL_CHANGES.md` are the reliable
     reference.
