# esp-tone

An ESP8266 (ESP-01) that serves a 440 Hz test tone over Wi-Fi, plus two ways to listen: a PC launcher
and an Android app. The transmitter is a small HTTP server, so it works on any network where the
listener can open a TCP connection to it. No broadcast or multicast is needed to *play*; multicast
(mDNS) is only used as a convenience to *find* the transmitter.

```
esp-tone/
├── firmware/
│   ├── test_tone/        Arduino sketch for the transmitter (the real firmware)
│   ├── mic_stream/       Same protocol, streaming an INMP441 I2S microphone instead of the tone
│   ├── flash_test/       Diagnostic sketch (flash + supply-voltage test); written, never run
│   └── build/            Compiled images, built locally (not in git: they embed Wi-Fi credentials)
├── pc-player/
│   └── tone_player.py    Finds a transmitter and opens its stream in VLC
├── android/              Android app (Kotlin, framework views + AndroidX SwipeRefreshLayout)
├── ESP8266-WiFi-MICROPHONE/  Clone of the EASSP microphone firmware with our local changes
│   ├── LOCAL_CHANGES.md      What we changed in it and why
│   └── build-esp01/          Current images: esp-mic-app.bin (update), esp-mic-merged.bin (blank chip)
└── tools/                Build/flash scripts, simulators and test tools (see NOTES.md)
```

**NOTES.md** has everything learned along the way: hardware, protocols, measurements, failures and their causes,
and how to move the whole setup to another machine.

## Protocol

The transmitter listens on TCP port **1234** and speaks plain HTTP/1.0.

| Request | Response |
|---|---|
| `GET /info` | One line of JSON, then close: `{"name","id","format":"wav","rate":16000,"channels":1,"bits":16,"freq":440,"busy"}`. Does not start audio. |
| any other path (e.g. `GET /`) | `200 audio/wav`, then a 44-byte WAV header with unknown length (`0xFFFFFFFF`), then an endless stream of 16 kHz mono 16-bit little-endian PCM, paced in real time. |

- **One listener at a time.** A second client from a different address gets `503 Busy`. A request from the
  *same* address as the current listener replaces it, because after a Wi-Fi drop the old connection
  looks open until TCP times out and would otherwise block the reconnect.
- **`id`** is the ESP8266 chip id (6 hex digits). It is the stable identity of a device; the name
  (`esp-tone`) is the same on every unit and the address can change.
- **mDNS / DNS-SD:** hostname `esp-tone.local`, service `_esptone._tcp` on port 1234, with TXT records
  `id`, `format`, `rate`, `channels`.
- Stream URLs are used **without a trailing slash**: `http://<ip>:1234`. That form was verified in VLC.

## Firmware (`firmware/test_tone`)

Arduino sketch for an ESP8266 on the esp8266 core 3.1.2. Before building, set `ssid` and `password` at
the top of `test_tone.ino` (they are in the source, so keep the file private).

Build with the Arduino IDE's bundled CLI (generic ESP8266 module, 1 MB flash):

```powershell
& "$env:LOCALAPPDATA\Programs\Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe" compile `
  --fqbn esp8266:esp8266:generic:eesz=1M --output-dir firmware\build firmware\test_tone
```

It uses about 276 KB of the 1 MB flash. Wi-Fi modem sleep is disabled (`WIFI_NONE_SLEEP`), because
sleeping made the ESP miss mDNS queries.

### Flashing

**Flash from the Mac, not from the Windows laptop.** The CH340 programmer stalls on Windows (see
Troubleshooting) and has worked every time on the Mac.

1. Build the sketch (see **Wi-Fi credentials** below), then copy `firmware/build/test_tone.ino.bin` to the Mac.
2. On the Mac, once: `python3 -m pip install --user --upgrade pip` then `python3 -m pip install --user esptool`
   (upgrading pip first avoids a failed `cryptography` source build).
3. Unplug the programmer, **hold BOOT** (GPIO0 to ground), plug it in, keep holding for about 2 seconds.
4. ```bash
   python3 -m esptool --chip esp8266 --port /dev/cu.usbserial-1410 --baud 115200 --before no_reset write_flash 0x0 ~/test_tone.ino.bin
   ```
   The port name is whatever `ls /dev/cu.*` shows for the CH340.

Check it: the serial monitor (115200) prints the IP address, and `http://<ip>:1234/info` returns the JSON.

## Microphone firmware (`firmware/mic_stream`)

Streams an INMP441 I2S microphone with the same protocol, 16 kHz mono 16-bit, so the Android app and
the PC launcher work unchanged. Hostname `esp-mic.local`; `/info` has `"source":"mic"` instead of `freq`.
Not yet tested with a microphone attached.

The ESP8266's I2S input pins are fixed in hardware and none of them is on the ESP-01 header, so they are
soldered directly to the ESP8266EX chip (0.4 mm pitch; pin 1 is the dot, numbering counter-clockwise):

| INMP441 | ESP8266 | Chip pin |
|---|---|---|
| SCK | GPIO13 (MTCK) | 12 |
| WS | GPIO14 (MTMS) | 9 (corner) |
| SD | GPIO12 (MTDI) | 10 |
| L/R | GND | |
| VDD / GND | 3.3 V / GND | |

Before soldering, with the module unpowered, pin 11 should beep to VCC and pin 13 to GND (confirms the
count), and pins 9, 10 and 12 to nothing.

The I2S runs in the ESP8266's 24-bit mode (48 clocks per frame, not the 64 the INMP441 datasheet asks
for); another ESP8266 + INMP441 project reports this works. At boot the sketch works out which I2S slot
the mic is on and prints it. Every 2 s it prints the peak level and any dropped samples
(115200 baud). Adjust `gainShift` if the level is too low or clips.

## PC launcher (`pc-player/tone_player.py`)

```bash
python tone_player.py                  # find the transmitter and open VLC
python tone_player.py --host 10.8.1.168   # skip discovery
python tone_player.py --diagnose       # report mDNS and the scan separately, with timings
python tone_player.py --print-only     # just print the URL
```

Discovery runs mDNS and a subnet scan **at the same time** and the first to find a device wins; it prints
which one. mDNS needs `python -m pip install zeroconf`. The scan reads the real netmask from the OS
(capped at a /20; override with `--subnet 10.8.0.0/22`), so it works when multicast is blocked.
Nothing is remembered between runs, on purpose, so intermittent mDNS failures stay visible.

## Android app (`android/`)

Kotlin, framework Views only, `minSdk 26`. Package `app.toneplayer`.

- Scans on launch and then every 15 s while the screen is shown (skipped while a stream plays or
  connects); **pull the list down** to rescan at once. Each scan (mDNS plus subnet scan) lists the transmitters. mDNS uses its own small multicast
  query (`Discovery.kt`), not `NsdManager`, which never reported the ESP on a Samsung phone.
- Tap a row to play; tap it again or press **Stop** to stop. **Add IP** adds a transmitter by address.
- The **Auto** checkbox marks the device to connect to at launch (one at a time). Known devices and their
  last address are remembered. **Long-press** a row to **Delete** it (for stale entries).
- If nothing is found, the status line says which network was scanned. **Add IP** also replaces a stale
  entry at the same address (carrying over its Auto flag), e.g. after the firmware gave the unit a real id.
- Auto-connect first asks the saved address; if it no longer answers it scans and reconnects to the same
  device by id at its new address.
- **Reconnects when the stream is interrupted**, until Stop is pressed: it retries the saved address,
  then rediscovers the device by id, with a back-off of 0.5, 1, 2, 4, then every 8 seconds. A first
  connection that never worked is reported, not retried.
- Plays from a foreground service with a Wi-Fi lock, so it keeps going with the screen off.
- Also plays microphones running the [ESP8266-WiFi-MICROPHONE](https://github.com/Dzantemir/ESP8266-WiFi-MICROPHONE)
  firmware (cloned, with our changes, in `ESP8266-WiFi-MICROPHONE/`), which speaks EASSP instead of HTTP: UDP control on
  port 3950 (DISCOVER / CONFIGURE / STOP), audio by UDP or TCP, PCM 16/24-bit or IMA ADPCM, any of its sample rates.
  They are found by a DISCOVER broadcast, listed with port 3950 and their MAC as id, and played at the ESP's real
  I2S rate (e.g. 48309 Hz for "48 kHz"). The app sends a DISCOVER heartbeat every 3 s (the firmware stops after
  15 s without one) and STOP when you press Stop. Until audio arrives it resends CONFIGURE every 0.5 s, since a
  lost CONFIGURE otherwise means silence. Code: `Eassp.kt`.
- **Microphone settings** (long-press an EASSP microphone): drop-downs for rate, bits, codec, channels,
  gain, AGC, I2S format and delays, transport and TX power, plus hostname and Wi-Fi. They are read on open
  with the firmware's own AT queries. **Apply** sends only what changed, then `AT+HOTRESTART`, offering a
  reboot for a hostname change. A console sends any AT command. This needs our firmware addition, an
  EASSP command that runs one AT line over the network (cmd `0x04`, reply `0x84` with the text). See
  `ESP8266-WiFi-MICROPHONE/LOCAL_CHANGES.md`. Code: `SettingsActivity.kt`.
- **Playback engine** (`JitterPlayer.kt`, used for both protocols): a jitter buffer feeding a low-latency
  AudioTrack in 5 ms blocks.
  - **Buffer** button: **Auto** (default: the worst gap between arrivals over the last 10 s plus 15 ms,
    40–250 ms) or a fixed 10–200 ms. Applies while playing. The status line shows delay, target,
    dropouts, ms filled and ms caught up.
  - Lost packets (sequence gaps) are filled **two-sided**: order-40 linear prediction forward from the audio
    before the gap and backward from the ~12.5 ms after it, crossfaded. Late audio (buffer ran dry) is
    extrapolated one-sided, full level for 10 ms, faded out by 60 ms.
  - Delay that builds up after a stall is removed one waveform period at a time (pitch-aligned, crossfaded).
  - Debug capture: `adb shell run-as app.toneplayer touch files/capture.raw.request`, play, then
    `adb exec-out run-as app.toneplayer cat files/capture.raw > capture.raw` (20 s, 16-bit LE, as played).
    On a simulated 3% loss, 170 fills produced no clicks (second difference > 300).

Build and install (JDK 21; the Android Studio JDK 25 also works):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
cd android
.\gradlew.bat assembleDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

Toolchain: AGP 9.5.0 with Gradle 9.6.0 (Kotlin is built into AGP 9), compile SDK 37. `local.properties`
points at the SDK. Logs are under the `TonePlayer` tag (`adb logcat -s TonePlayer`).

## Known limits

- Discovery cannot cross subnets: mDNS does not route and the scan covers only the listener's own network.
  Use **Add IP** / `--host` for a transmitter on another subnet. (The test phone is on 192.168.100.x and the
  ESP on 10.8.x.x.)
- One listener at a time.
- Audio focus (phone calls, other apps) is not handled.
- Not tested end to end: automatic discovery and "device moved to a new address" on the phone, which needs
  the phone on the same network as the ESP.

## Troubleshooting

**Flashing from Windows fails (`Write timeout`).** The CH340 USB-serial path on the ZBOOK stalls partway
through a transfer, on driver versions 3.4, 3.5, 3.9 and 4.0, directly or through a hub, with and without a
supply capacitor, at 9600 to 115200 baud. Newer WCH drivers also fail to open the port at all, and Windows
rebinds the newest driver whenever the programmer is plugged into a different USB port. Flash from the Mac.

**The chip is not in bootloader mode.** GPIO0 must be low when the chip powers up. Plugging the programmer
in is the only reset (DTR/RTS are not wired to the chip). `esptool` reporting `Timed out waiting for packet
header` or `Invalid head of packet` means it is running normally. If `http://<ip>:1234/info` answers, the
chip is running the firmware.

**mDNS is unreliable.** Run `python pc-player/tone_player.py --diagnose` several times. If mDNS fails while the
scan works, multicast is the problem on that network path; if both work, look at the ESP side.


## Wi-Fi credentials

Nothing in this repository contains a real network passphrase. Each firmware target reads its
credentials from a local `wifi_config.h` that git ignores, with a committed template beside it:

| Target | Copy this | To this |
|---|---|---|
| `firmware/test_tone` | `wifi_config.h.example` | `firmware/test_tone/wifi_config.h` |
| `firmware/mic_stream` | `wifi_config.h.example` | `firmware/mic_stream/wifi_config.h` |
| `firmware-v2` | `main/wifi_config.h.example` | `firmware-v2/main/wifi_config.h` |

The Arduino sketches compile the credentials into the image, so `firmware/build/*.bin` is **not**
committed — an image built from your own `wifi_config.h` carries your passphrase in cleartext. Build
it locally before flashing. The EASSP firmware in `ESP8266-WiFi-MICROPHONE/` takes its credentials at
runtime over AT commands instead, so the images in `build-esp01/` are safe to publish and are checked in.

## `ESP8266-WiFi-MICROPHONE/` is a vendored fork

That directory is a fork of [Dzantemir/ESP8266-WiFi-MICROPHONE](https://github.com/Dzantemir/ESP8266-WiFi-MICROPHONE),
branched from commit `7ff3078`, with substantial local changes — `LOCAL_CHANGES.md` lists them and why.
It is vendored as plain files rather than a submodule, because the changes were never committed upstream
and a submodule records only a commit pointer.

The original clone's history is kept out of the way at `ESP8266-WiFi-MICROPHONE/.git-upstream/`, which
this repository ignores. To diff against upstream, point git at it explicitly:

```bash
cd ESP8266-WiFi-MICROPHONE && git --git-dir=.git-upstream diff
```

Upstream is MIT-licensed; its `LICENSE` is retained in that directory.

## License

MIT, see [LICENSE](LICENSE). The vendored fork in `ESP8266-WiFi-MICROPHONE/` is MIT-licensed
upstream and keeps its own `LICENSE` file.
