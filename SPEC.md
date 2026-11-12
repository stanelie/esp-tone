# esp-tone — firmware specification (for a from-scratch implementation)

Status: **draft for review.** Written 2026-11-04 after a long debugging session on the existing
firmware (a fork of `Dzantemir/ESP8266-WiFi-MICROPHONE` with extensive local patches). Purpose: to
carry the *knowledge* forward without carrying the code forward.

---

## 1. What this product is

A small, cheap wireless microphone. An ESP8266 with an I2S MEMS mic captures audio and streams it
over Wi-Fi to an Android phone, which plays it live.

The appeal of the ESP8266 is explicitly **size and cost**. If it can do the job it should be kept;
if it provably cannot, that is an acceptable finding and the platform changes.

## 2. Requirements

### 2.1 Must have
- **R1 — Reliable connection.** The device is reachable whenever it is powered and in range.
- **R2 — Fast recovery.** If the link fails for any reason, recovery is automatic and takes
  **seconds, not minutes**. The user must never have to power-cycle the device.
- **R3 — Continuous audio.** Playback does not stop and start. (The current firmware fails this —
  see §5.1.) Brief concealed packet loss is acceptable; audible gaps are not.
- **R4 — High gain.** Quiet sounds must be audible. Large digital gain values are a *requirement*,
  not a misconfiguration; in this setup they do not clip.
- **R5 — Settings persist** across power loss, and a firmware update must not erase them.
- **R6 — Discovery.** The phone finds the device on the LAN without manual configuration, with a
  manual "add by IP" fallback for cross-subnet cases.

### 2.2 Should have
- **R7 — Configurable** sample rate, bit depth, codec, channel and **fixed gain** from the phone.
  **AGC is explicitly deferred** until the core features are reliable; fixed gain is enough.
- **R8 — Observable.** It is possible to find out what the device is doing *while it is misbehaving*
  (see §5.6 — the existing log channel becomes unreadable in exactly that state).

### 2.3a No signal processing on the path — hi-fi means unaltered
**Decision: the device and the player apply no filtering of any kind.** No high-pass, no noise
reduction, no AGC, no limiting. The goal is to reproduce what the microphone hears as faithfully as
possible; the listener judges the result.

This was tested against a real temptation: measurement showed **93–98% of captured energy sits below
200 Hz** (room rumble; the mic is only flat from 60 Hz), so with high gain that content dominates the
headroom and is what pushes transients into clipping. A high-pass around 80–100 Hz would allow
noticeably more usable gain. **Rejected anyway** — it would discard real signal to make a number
look better, and the operator can hear clipping and manage gain themselves.

Gain (§2.4) is the one exception, and it is deliberately a plain multiplier with saturation: no
dynamics, nothing that changes the shape of the waveform.

### 2.3 Non-goals (for v1)
- **Latency** — not a focus at all for now.
- **Multi-client streaming** — one listener is enough; revisit later.
- **AGC** — deferred (see R7).
- **Protocol compatibility** — the wire protocol **may change**. The goal is a reliable connection
  and reliable reconnection, not preserving EASSP. Both ends are being rewritten anyway.

### 2.4 Audio quality target — bounded by the microphone, not by 16/44.1
The stated wish was "at least as good as 16-bit/44.1 kHz, lossy is acceptable if a codec can do it".
Per the INMP441 datasheet (§4.1) that target is **above what the hardware can produce**, so the
real requirement is: **preserve what the mic can actually deliver — ~61 dB SNR and 60 Hz–15 kHz.**

| Option | Bitrate | Quality vs the mic |
|---|---|---|
| 44.1 kHz / 16-bit PCM (current) | 705.6 kbit/s | full, but over-sampled for a 15 kHz mic |
| **32 kHz / 16-bit PCM** | **512 kbit/s** | **full** — Nyquist 16 kHz still covers 15 kHz |
| 32 kHz / IMA ADPCM | 128 kbit/s | SNR-limited (~30 dB) — audibly below the mic |
| 44.1 kHz / IMA ADPCM | 176.4 kbit/s | same SNR limit |

**Where gain belongs: in the player, not the device.** The mic's own noise floor (~-61 dBFS, from
61 dB SNR) sits ~35 dB *above* 16-bit quantization noise (-96 dBFS), so 16-bit capture already
preserves everything the mic can resolve and there is no low-level detail for gain-before-truncation
to rescue. Meanwhile device-side gain with saturation bakes clipping **irreversibly** into the
transmitted stream, whereas player-side gain leaves the wire data pristine and can always be turned
back down — which matters precisely because R4 wants aggressive gain. The device therefore ships raw
samples (gain x1) and the player applies gain.
**Exception:** if the device ever encodes to ADPCM, gain must move back *before* the encoder, since
a quiet signal quantizes badly into 4 bits.

**Conclusion:** cut the *sample rate*, not the bit depth, before reaching for a lossy codec. 32 kHz
16-bit PCM is the most promising quality-per-bit point, but **whether 32 kHz is audibly as good as
44.1 kHz on this mic is an open listening test** (§4.1) — so both rates must be selectable at
runtime and A/B'd. ADPCM stays available as the fallback if 512 kbit/s proves unsustainable — but it
is a genuine quality loss, not a free win, and no better codec (Opus, SBC) fits this CPU.

## 3. Hardware

| Item | Detail |
|---|---|
| MCU | ESP8266 (ESP-01), 1 MB flash, ~50 KB usable DRAM, single core |
| Mic | INMP441 I2S MEMS |
| Programmer | CH340 USB-serial, **no working auto-reset** |
| Test phone | Galaxy S8 (SM-G950W), Android 9 |

**Programmer quirk (costs time every flash):** flashing requires holding BOOT (GPIO0 low) while
plugging in, and after flashing the chip will **not** restart into the app — it needs a
power-cycle. `--before no_reset` is required; the RTS hard-reset does nothing.

## 4. Preserved knowledge (hard-won; do not re-derive)

### 4.1 I2S / mic — the INMP441 is NOT a 24-bit microphone
Verified against the datasheet:

| Spec | Value | Meaning |
|---|---|---|
| I²S word length | 24-bit | **container only** |
| SNR | **61 dBA** | ≈ **10 effective bits** of instantaneous signal |
| Dynamic range | **87 dB** | ≈ 14.5 bits noise-floor-to-max |
| Sensitivity | −26 dBFS @ 94 dB SPL | |
| Frequency response | **flat 60 Hz – 15 kHz** | **flat region, NOT a cutoff** — above 15 kHz the response rolls off but content is still captured |

Therefore: **16-bit capture (96 dB) exceeds the mic's 87 dB range and loses nothing.** 24-bit buys
no fidelity — do not pay bandwidth for it.

On sample rate: 15 kHz is where the response stops being **flat**, not where it stops. The mic still
picks up above that with roll-off, so 32 kHz is *probably* sufficient but this is **not settled by
the datasheet**. **The sample rate must be switchable at runtime (32 kHz / 44.1 kHz at minimum) and
judged by listening**, not decided on paper.

- Format **Philips** (`comm_format=0`), channel **left** (mic L/R pin to GND), RX delays
  `sd=0, ws=0, bck=0`.
- The **first DMA read after init returns saturated/garbage samples** — discard it. (Mistaking this
  for clipping cost real time in the last session.)
- **`i2s_set_pin()` must be called BEFORE `i2s_driver_install()`.** Backwards, RX is never clocked
  and every `i2s_read` just times out. (Measured: `frames` frozen, `i2s_to` climbing.)
- **I2S init is unreliable** and can come up dead even in the correct order — capture ran for ~2 s
  then stopped, on one boot but not the next. A driver **uninstall + reinstall cures it instantly**.
  v2 therefore (a) initialises I2S *before* Wi-Fi bring-up, on the theory that the init races Wi-Fi,
  and (b) keeps a stall detector that re-inits when frames stop advancing while timeouts climb. The
  `reinit=` counter says whether the reorder was actually sufficient.
- Also drain ~6 small reads after install: the DMA pipeline holds stale buffers and the INMP441 has
  a ~10-15 ms startup transient.

### 4.2 Real sample rates — important
The I2S clock derives from 160 MHz through two integer dividers, so the *nominal* rate is not the
*actual* rate:

| Nominal | Actual |
|---|---|
| 44100 | **43860** |
| 48000 | **48309** |

The player **must** configure its output at the actual rate, or audio drifts. Also, these odd rates
keep Android's AudioTrack off the fast-mixer path, adding 39–49 ms output latency.

### 4.3 Codecs and bitrates (mono, 44.1 kHz nominal)

| Codec | Bits/sample | Bitrate |
|---|---|---|
| PCM 16-bit | 16 | **705,600 bps** |
| IMA ADPCM (DVI4, RFC 3551) | 4 | **176,400 bps** |

ADPCM framing: 4-byte header (predictor LE, step index, 0), first sample in the **high** nibble.
705 kbit/s of PCM is near this chip's practical ceiling and is what forced all the exotic memory
handling in the old firmware. **176 kbit/s ADPCM should be the default.**

### 4.4 Wi-Fi settings that matter
- **Power save must be OFF.** `MAX_MODEM` (the SDK default) makes the radio doze between beacons and
  floods `sendto` with `ENOMEM` (errno 12) under audio load.
- **Raise the TX buffer count** (10 was used).
- **Scan all channels and join the strongest AP.** The SDK's fast scan joins the *first* AP matching
  the SSID from channel 1 upward; in a multi-AP building that picks a weak one on a busy channel and
  produces exactly the ENOMEM/glitching above.
- `ENOMEM` on send means **local congestion, not a dead link.** Retry; never treat it as link death.

### 4.5 Flash layout / settings
- App at **0x10000** — updating this **keeps** Wi-Fi credentials and settings.
- Full image at **0x0** — **erases NVS**; Wi-Fi must be set again over serial.
- Audio settings are saved immediately but only take effect when the **stream (re)starts**.

### 4.6 Android playback
- Samsung's `NsdManager` never reported the device — discovery is hand-rolled (mDNS PTR query +
  subnet scan + broadcast). Keep that.
- A jitter buffer feeding AudioTrack in small blocks, with packet-loss concealment, is needed.
  Order-40 LPC two-sided fill worked well (pitch-period repetition did not: ~115 clicks in 19 s).
- Latency floor is the network, not the code.

### 4.7 Wire protocol (EASSP) — keep it, the app already speaks it
UDP port **3950**. Header: magic `0xEA 0x55`, version 1, cmd, seq (LE16), payload_len (LE16).

| Cmd | Name | Notes |
|---|---|---|
| 0x01 | DISCOVER | also the in-stream heartbeat |
| 0x02 | CONFIGURE | payload = stream port, **big-endian** |
| 0x03 | STOP | |
| 0x04 | AT | local addition: run an AT command over the network |
| 0x81 | INFO | reply + broadcast announcement every 1–5 s while idle |
| 0x84 | AT_REPLY | |

The device stops streaming if it sees no DISCOVER for **15 s**.

### 4.8 Build toolchain (macOS)
- SDK must be the **`release/v3.4` branch**, not the `v3.4` tag (the tag lacks
  `ESP_ERR_NVS_NEW_VERSION_FOUND`). Submodules need a **full** (non-shallow) update.
- Do **not** use `export.sh` on macOS — it resolves bare `python` to the Xcode stub. Drive
  `idf.py` with the SDK venv's python directly (see `tools/build_mic.sh`).
- venv pins: `cryptography==3.3.2`, `setuptools<81`, `cmake==3.22.6`, `ninja`.
- **The ESP8266 SDK has no heap poisoning and no `heap_caps_check_integrity`** (those are ESP32).

## 5. Defects proven in the existing firmware

Each was *measured* in the last session, not inferred.

### 5.1 Heap corruption while streaming — the root cause
With the allocator canary enabled, one 20 s stream produced:
```
heap_canary_hit[0]=len=448  off=64892 DRAM broken=tail
heap_canary_hit[1]=len=1032 off=65340 DRAM broken=headtail
```
The blocks are **adjacent** (64892+448 = 65340): a single contiguous write runs ~1.5 KB past its
buffer through the neighbour. A wrong length in a copy. Raw PCM gave 5 violations, ADPCM 2 — so
lowering the bitrate reduces but does **not** remove it.

This explains every symptom that no network theory could:
- `esp_log` output degenerates into garbage while AT replies stay clean,
- the Wi-Fi **data** path dies while `wifi_connected=YES`, `error=NONE`, RSSI healthy,
- **reassociation never helps; a reboot fixes it instantly** (corrupt RAM survives a rejoin).

**The exact offending line was not found** — deliberately. It needs unpicking several custom layers
(`heap_caps_malloc` wrappers → `mxr_malloc` with IRAM-preference hints → pooled task buffers) and
the sizes don't map onto any visible frame/DMA figure. This is the main argument for starting over.

### 5.2 Gateway-ping "link check" — actively harmful, do not reimplement
Pinged the gateway and rejoined after 3 misses. Under audio load the ping's own `send()` fails with
ENOMEM, which was miscounted as "gateway silent" — so it tore down a healthy link (RSSI −47) after
*every* stream, and the rejoin then missed DHCP and fell back to a cached static IP.

### 5.3 Current-server hijack
`handle_discover` let **any** host's DISCOVER claim the "current server" slot while idle. Because a
stream starts slightly before the state flips to STREAMING, a DISCOVER landing in that window stuck
— and the real client then failed the sender check forever: every heartbeat "ignored (streaming)",
starving the 15 s watchdog and misdirecting INFO. Any device scanning the LAN could trigger it.

### 5.4 Liveness signals that do NOT work
Three were tried; all produce false positives. Do not reuse:
- **Gateway ping** — third party; may not exist; its own send fails under load.
- **Local send errors** — `ENOMEM` is congestion, not death (audio still played through it).
- **lwIP RX silence** — beacons never reach lwIP, and a dedicated AP with no other clients is
  *legitimately* silent for hours.

The only authoritative signal is **the receiver confirming it is getting data**. On a quiet AP with
no peer there is no local way to detect a dead data path — design for that, don't pretend otherwise.

### 5.5 Recovery that works
**Only a reboot clears the wedged state.** Reassociation does not (verified: unreachable for >4 min
after one, then `AT+RST` fixed it immediately). An ESP8266 reboot + rejoin is a few seconds, so a
reboot is an acceptable recovery action.

### 5.6 ~~Observability fails exactly when needed~~ — WITHDRAWN, this was a measurement error
Originally recorded as "the device emits ~50 KB/s of unreadable garbage while AT still answers, so
the logging subsystem must be corrupted". **That was wrong.** The console baud is **74880** (the SDK
default, and `CONFIG_ESP_CONSOLE_UART_BAUDRATE=74880` in the old firmware's sdkconfig too) while
every serial tool here was reading at **115200**. Reading 74880 output at 115200 produces precisely
that signature: mostly garbage with occasional accidentally-valid fragments (the `)\r\n` / `LE)`
"flood"). AT replies survived because the AT layer writes with `uart_write_bytes` after the UART has
been reconfigured.

The repeated-fragment pattern also appears on the clean v2 firmware at the correct baud, so it is an
artifact of the reader, not the device.

**Lessons that do stand:** set the console baud **explicitly** (v2 pins 115200) so tools cannot
silently mismatch, and keep a diagnostic channel that works when things are broken — just not for
the reason originally claimed. The memory-corruption case now rests on the canary counters in §5.1
alone, which came back over clean AT replies and remain valid.

### 5.7 UI/UX defects worth keeping fixed
- `HOTRESTART` only applies settings **while streaming**; idle it just saves them. The UI must say
  "saved — takes effect when playback starts" instead of looking applied.
- **Gain is bypassed entirely whenever AGC is on.** The UI must disable the gain control then.

## 6. Proposed architecture for the rewrite

Principles: **no custom allocator, no custom I2S driver, one connection manager, ADPCM by default.**

1. **Capture** — SDK I2S driver, fixed-size frames, discard the first read. Static/pool buffers
   sized once at init; no dynamic allocation on the audio path at all.
2. **Encode** — IMA ADPCM (default) or PCM. One buffer in, one buffer out, lengths derived from a
   single frame-size constant so a codec change cannot desynchronise a size.
3. **Transport** — UDP, EASSP as in §4.7.
4. **Connection manager** — exactly one place owns link state. Receiver-confirmed liveness while
   streaming; reboot as the recovery action; no gateway pings, no send-errno heuristics.
5. **Diagnostics** — counters readable over AT, independent of the log path (§5.6).

Memory discipline is the whole point: at 176 kbit/s with static buffers, the pressure that forced
IRAM gymnastics disappears, and with it the corruption class in §5.1.

## 6b. Measured results — the v2 spike (2026-11-04)

A deliberately minimal firmware (`firmware-v2/`, ~340 lines: stock heap, stock I2S driver, static
buffers only, one task doing capture *and* send) was built to answer the platform question before
writing any product code.

| Measurement | Result |
|---|---|
| Sustained bitrate, 32 kHz/16-bit PCM | **519–520 kbit/s, metronome-steady** |
| Packet loss, 60 s run | **0.000 %** (6008 packets, 0 lost, 0 reordered) |
| Packet loss, 45 s run | **0.067 %** — 3 packets, each matching a `send_fail errno=12` |
| Buffer overruns (`GUARD`) | **0** throughout |
| I2S timeouts / short reads once initialised | **0** |
| Free heap | **~66 KB, stable** (vs ~57–59 KB on the old firmware) |
| Audio quality, Mac WAV capture | clean, **no dropouts** |
| Audio quality, **Android app, live** | clean, **no dropouts** |

**Conclusions:**
1. **The ESP8266 is viable.** It sustains full microphone quality (32 kHz/16-bit PCM, no codec, no
   compression) with essentially zero loss. Small and cheap stays on the table.
2. **The corruption was the old firmware's own.** `GUARD=0` throughout, with static buffers and the
   stock heap, against the canary violations measured on the old build (§5.1).
3. **R3 is satisfied** — the stop/start dropouts are **gone**, on the phone as well as the Mac. They
   were a property of the old firmware, not of the chip, the network or the app's playback.
4. `ENOMEM` on send is confirmed as *minor local congestion*: 3 packets in 45 s, and the receiver's
   "lost" count matched the device's `send_fail` count exactly. Concealable; never a link failure.

Caveat on method: two ~1 s *delivery* stalls appeared in one Mac run with **zero** packet loss
(packets delayed, then bursting). The device was sending steadily throughout, so those were
receive-side (macOS Wi-Fi scans are the likely cause) and did not reproduce. A WAV capture cannot
reveal them — a file has no real-time constraint — which is why the live phone test was the one that
mattered.

## 7. Open questions (need answers or measurement)

1. ~~What causes the audio to stop and start (R3)?~~ — **RESOLVED** (§6b): gone on the clean
   firmware, on both the Mac and the phone. It was the old firmware.
2. ~~Gain headroom vs bit depth~~ — **RESOLVED** (§4.1): the mic's 87 dB dynamic range fits inside
   16-bit, so 16-bit is sufficient and 24-bit is wasted bandwidth.
3. ~~What bitrate can the ESP8266 sustain~~ — **RESOLVED** (§6b): **520 kbit/s with 0.000–0.067 %
   loss.** 32 kHz/16-bit PCM is sustainable, so no codec is needed and ADPCM stays an unused option.
4. ~~Is AGC wanted~~ — **RESOLVED**: deferred past v1; fixed gain only.
5. ~~Keep EASSP~~ — **RESOLVED**: the protocol may change; reliability is the goal.
6. **Is 32 kHz audibly as good as 44.1 kHz on this mic?** Still an open *listening* test — 15 kHz
   is where the response stops being flat, not where it stops (§4.1). Both rates are switchable at
   runtime; the A/B has not been done yet.
7. **Does the I2S `reinit=` counter stay at 0** now that init runs before Wi-Fi? That decides whether
   the race theory in §4.1 was right or the self-heal is doing the work.

---

## 8. Settings and control — design discussion

**Constraint:** settings are changed **from the app only**. No computer, no serial, no AT console in
normal use. The app's legacy AT-command screen has been **deleted** (along with the now-unused
`Eassp.at()` helper), so the settings protocol in §8.2 is the only path. (The old firmware's AT interface assumed a terminal; it also cost thousands of lines of
text-parsing on the most constrained part of the system — `at_cmd.c` + `at_handlers.c`.)

### 8.1 What is a setting, and what is just a constant
**Test: if there is no circumstance in which a user would change it, it is a constant, not a
setting.** Every setting costs permanent UI surface, a validation path, an NVS field, and one more
way to silently break the audio. The old firmware exposed nine of them; that is what its confusing
settings screen cost.

Applying that test:

| Item | Verdict | Why |
|---|---|---|
| **Sample rate** (32 kHz / 44.1 kHz) | **user setting** | a real, audible choice (§4.1 A/B) |
| **Wi-Fi credentials, hostname** | **user setting** | provisioning (§8.4) |
| **Codec** (PCM / ADPCM) | **probably** | genuine quality-vs-bandwidth trade on a weak link |
| Bit depth (16 / 24) | **constant** | 16 already exceeds what the mic resolves (§4.1) |
| Channel (left / right / stereo) | **constant** | fixed by the mic's L/R wiring; stereo is different hardware |
| I2S format (Philips / LSB) | **constant** | a property of the mic chip |
| I2S RX delays (`sd`,`ws`,`bck`) | **constant** | board-level signal timing, found once at bring-up |
| TX power | **diagnostic** | not user-facing |

So **two or three** user settings, not nine. The hardware-determined values are compile-time
constants in the firmware (v2 hardcodes Philips / left / zero delays and gets zero I2S timeouts and
clean audio). They would only need to vary across mic models or board revisions — a developer need,
which belongs on the development-only path, not in the app.

Everything else belongs in the player, where there is compute headroom and changes are
non-destructive: **gain** (§2.4), AGC (deferred), jitter-buffer target.

### 8.2 Recommended mechanism: a self-describing settings table
Two messages on the existing control socket:

- **`GET_SETTINGS`** → the device returns, for each setting: `id`, `kind` (enum / integer),
  `current value`, `allowed values` (or min/max), a short `label`, and an `applicable` flag.
- **`SET_SETTING(id, value)`** → the device validates against its own table, persists to NVS,
  applies, and replies with the resulting value or an error code.

**The app renders its UI from that descriptor and hardcodes nothing.** This is the important part,
because it structurally removes the whole class of bug we hit:

| Bug we actually had | Why the descriptor prevents it |
|---|---|
| App offered gain 0–64 when only 4–8 was useful at 16-bit | the device reports the useful range |
| App offered gain while AGC was on, where it is silently ignored | the device reports `applicable=false` |
| App showed saved values as though they were live | no longer possible — see §8.3 |

Those were fixed one at a time by patching the app. The descriptor makes the device the single
source of truth so they cannot recur, and new settings appear in the UI with no app change.

Binary and typed rather than text: no parser on the constrained device, and the descriptor already
makes it self-documenting. A small development-only text path over serial is fine, but it is not the
product interface.

### 8.3 Abolish the "apply later" concept
The old design saved a setting and then needed `AT+HOTRESTART` to apply it — which only worked
*while streaming*, producing the "I changed it and nothing happened" confusion more than once.

**v2 applies settings immediately**, restarting its own capture internally when a change requires it.
The player already handles a mid-stream format change (it builds a new `AudioTrack` when the format
differs), so no cooperation is needed and there is no "saved but not live" state to explain. A brief
audio gap on a deliberate settings change is acceptable; silent non-application is not.

### 8.4 Wi-Fi provisioning without a computer
Credentials are the one chicken-and-egg: they cannot be set over the Wi-Fi they configure. With no
serial and no PC available, the answer is **SoftAP fallback** — if the device cannot join for N
seconds it raises its own access point, the phone connects to it and configures credentials, and it
returns to station mode. The ESP8266 supports AP+STA, so this needs no extra hardware and keeps the
"app only" rule intact even for first-time setup and for a changed router password.

### 8.5 Known limitation, inherited
EASSP was unauthenticated: anyone on the LAN could change settings, and a Wi-Fi password set from
the phone crossed the LAN in clear text. For v1 a trusted LAN is an acceptable assumption, but it
should be a recorded decision rather than an accident. Provisioning over SoftAP (§8.4) at least
keeps the password off the shared network.

---

## 9. Measured: capture-path fidelity (pink noise, 2026-11-04)

Steady pink-noise source, four captures, same session.

### 9.1 ~~The 16-bit DMA packing bug~~ — SUPERSEDED by 9.4, the diagnosis was wrong
The claim here was that the ESP8266 packs **two different** 16-bit samples per 32-bit DMA word as
`[S_n hi16 | S_n+1 lo16]`, so adjacent pairs arrive transposed and must be de-swapped. The
measurement that motivated it was real — de-swapping removed a +18 dB-at-8 kHz anomaly, and
re-swapping a capture offline still makes things measurably worse — but the mechanism was not.

The word does not hold two samples. It holds **the same sample twice** (§9.4). De-swapping looked
like it helped because it happened to preserve the duplicate pairing; it was never putting samples
back in time order, because they were never out of order.

### 9.2 24-bit capture does not work on this platform
| capture width | nominal | true rate | packets/s |
|---|---|---|---|
| 16-bit | 32000 | 32051 | 100 |
| 24-bit | 32000 | **21367** | 66 |

At 24-bit the achieved rate is exactly **2/3** of nominal. **Root cause found** (SDK source,
`components/esp8266/driver/i2s.c:456` `i2s_set_rate`): the driver computes its two dividers from
`I2S_BASE_CLK / 32` and **never looks at `bits_per_sample`**. It assumes a 32-BCK frame (two 16-bit
slots). At 24-bit the frame is 48 BCK wide, so the real WS rate is `32/48 = 2/3` of the requested
one. So the requested rate is unobtainable at 24-bit, and this is a driver limitation, not a
hardware one.

**Consequence:** the INMP441 specifies `fSCK = 64 x fWS`; the stock ESP8266 driver allows only 16-
or 24-bit words (32 or 48 SCK per stereo frame) and 24-bit breaks the rate. **The mic therefore
cannot be clocked to spec on this platform.** Use 16-bit capture.

### 9.3 ~~Open: a flat ~7 dB level offset between rates~~ — ROOT-CAUSED, see 9.5

### 9.4 Every sample was delivered twice — half the bandwidth was missing (2026-11-05)
Probing with a **steady 1 kHz tone** instead of pink noise (a sine's envelope should be dead flat,
so any modulation is the device's own) turned up the largest capture defect so far.

**Evidence, from `/tmp/tone32.wav` (nominal 32 kHz) and `/tmp/tone44.wav` (nominal 44.1 kHz):**

| Measurement | 32 kHz | 44.1 kHz | A correct capture |
|---|---|---|---|
| exact repeats at lag 1 | **100.00 %** | 50.18 % / 49.88 % at lag 3 | ~0.1 % (chance) |
| even- vs odd-index sample streams | **−2.0001 samples** apart | −2.00 | +1 sample |
| gain / DC mismatch, even vs odd | 0.000 dB / 0.01 LSB | −0.001 dB / 0.08 LSB | 0 |
| mirror image of the tone at `sr/2 − 1 kHz` | **−20.2 dBc** | **−16.6 dBc** | absent |
| HF-noise envelope ripple | 1.39 % | 56 % | ~14 % (noise-like) |

The raw samples say it outright: `… 716 805 805 743 743 555 555 247 247 …`.

**What it is.** In 16-bit `I2S_CHANNEL_FMT_ONLY_LEFT` the peripheral runs WS at **half** the rate
the driver was asked for and hands each sample to DMA **twice**. A ±1-sample transposition cannot
produce a −2-sample offset between the even and odd streams — only duplication can — and the
mirror image at `sr/2 − f` is the textbook zero-order-hold signature, at the level the hold's
`cos(pi f / sr)` rolloff predicts (−20 dB at 15 kHz for a 32 kHz stream: measured −20.15).

**Why it hid for so long.** Two errors cancelled. `compute_actual_rate()` reported a rate twice the
true one, and the stream carried every sample twice, so a WAV written at the reported rate played
back at exactly the right speed — the 1 kHz tone measured **1000.00 Hz**. Nothing in rate, loss,
bitrate or packet timing was off. What was lost was half the audio bandwidth, with everything
above `sr/4` folded back into the band.

**The fix** (`firmware-v2`): ask the peripheral for `2 x` the target rate so WS lands on the
target, then discard the duplicate of each pair, choosing the pair alignment from the data (the
duplicates are bit-exact, so the phase with more matching pairs is the real one — it is not fixed,
an odd frame length flips it frame to frame). The de-swap of §9.1 is removed. `dma_buf_len` went
96 → 256, because the oversampled read (1760 B at 44.1 kHz) no longer fits the old 1536 B ring.
A `dup=NN%` counter in the stats line keeps the assumption honest: it should read ~100, and near 0
would mean the peripheral is not duplicating and the decimation is discarding real audio.

**Also explained by this:** the 44.1 kHz-only 50 Hz artifact. 441 samples per frame is odd, so the
pair alignment flipped every frame — a 2-frame period — which is why the HF-noise envelope at
44.1 kHz carried a **+52.9 dB line at 49.86 Hz with odd harmonics** (149 / 249 / 337 Hz: a square
wave) and 56 % ripple, against 1.39 % at 32 kHz where 320 samples is even. Rounding the frame to an
even length fixes that independently, and is also in.

**Not yet explained:** the ~7 dB level offset of §9.3, and the `reinit=` that fires on most boots.

**Tone capture also showed the analogue path is fine:** THD at −18 dBFS was H2 −54 dB, H3 −70 dB,
H4 −76 dB (32 kHz) — no clock or bit-alignment distortion, no gain or DC mismatch between the
interleaved streams. Whatever remains is not the microphone or the gain stage.

### 9.5 The DMA structure, and the level offset, both root-caused (2026-11-05)

**What DMA actually delivers.** Not two different samples per 32-bit word (§9.1), and not simply
each sample twice (§9.4's first reading). The undecimated stream, captured with the diagnostic
`P1` passthrough, is:

    y[2k] = s[k]        y[2k+1] = s[k-1]

Each word is `[new sample | previous sample]`. Three independent confirmations:

| Prediction | Measured |
|---|---|
| 100 % bit-exact repeats at **lag 3** | 100 % (`… -35 -38 -39 -35 -46 -39 -56 -46 …`) |
| `Y(z) = S(z^2)(1 + z^-3)` notches at `sr/6` | −19.5 dB at 10 kHz, `sr/6` = 10684 Hz |
| no genuine content above `sr/4` | upper half is all image, none independent |

**Consequence: decimating by 2 is exact and needs no filter.** The discarded words are copies, not
distinct samples, so there is no aliasing — which matters because §2.3a forbids filtering. Either
phase may be taken: both carry the whole signal, one sample of delay apart. What must not happen is
the phase **changing** mid-stream, hence the forced even frame length.

Note the earlier `dup=10%` reading was a measurement bug in the firmware's own sanity counter: it
checked lag 1, where the duplicate is not. It checks lag 3 now.

**The level offset between rates: `bck_div`.** With a steady 1 kHz tone and the rate **pinned** at
32051 Hz so only the divider varied:

| bck_div | 1 kHz tone, vs bck=2 |
|---|---|
| 2 | 0.00 dB |
| 3 | +5.80 |
| 6 | +5.67 |
| 13 | +5.00 |
| 26 | +5.24 |
| 39 | +6.23 |

Repeated four times alternating bck 2 / bck 3: **−52.87 ±0.71 dB** vs **−46.74 ±0.18 dB**, a gap of
6.13 dB against one bit = 6.02 dB. Flat across the spectrum, noise floor tracking the signal, so it
is a **one-bit shift of the 16-bit window taken out of the mic's 24-bit word**, selected by
`bck_div`. 32 kHz landed on `bck_div=2` and 44.1 kHz on 3 — that was the whole mystery, and it is
why the offset *flipped sign* when the capture clocking changed.

**Fix:** `I2S_BCK_MIN = 3`. Both rates keep their exact values (32 kHz -> bck 3 / clkm 26 = the same
64102.56 Hz; 44.1 kHz -> bck 3 / clkm 19 = the same 87719.3 Hz) and now share one alignment, so
switching rate no longer changes loudness. The louder alignment is chosen deliberately: it keeps one
more of the mic word's low bits, the right side to err on when the point is hearing quiet sounds.

**Do not read SNR into the divider sweep.** A first pass appeared to show `bck_div=2` with a 10 dB
better noise floor. Repeating it showed the noise floor swinging **±28 dB within a single setting** —
that was room noise. The divider affects level only. (General lesson: the tone level is a reliable
measurement in this room, the broadband noise floor is not.)

### 9.6 The divider pair matters twice over — two separate 44.1 kHz faults
The SDK's `i2s_set_rate` searches `bck_div = i`, `clkm_div = j` from 1 upward and applies integer
`abs()` to a float difference, so every candidate within 1 Hz ties at 0 and **the first one found
wins**. For an oversampled 44.1 kHz (88200) that is `i=1, j=57`, and `bck_div = 1` produces no
clock at all: `i2s_read` times out forever, logging `capture dead after switch` x3 and looping the
stall detector, while 32 kHz worked because its search happens to land on `i=2`.

`i=3, j=19` gives the **identical** 87719.3 Hz, so the rate was never unreachable — only the
divider was bad. v2 therefore picks the pair itself with `bck_div >= I2S_BCK_MIN` and writes
`bck_div_num` / `clkm_div_num` straight to the peripheral after `i2s_driver_install`, and derives
the reported rate from the dividers it wrote rather than re-deriving what the SDK might have done.

## 10. Stream ownership — one listener, held by a lease (2026-11-05)

v2 reintroduced the §5.3 "current-server hijack" defect and added a second one. Both were found the
hard way: a stray test client on the development Mac sent `CONFIGURE`, silently took the stream from
the phone, and **nothing ever gave it back**. For minutes afterwards the device reported
`streaming=1` at 715 kbit/s into a socket that had been closed, while the phone showed "No audio
from the microphone". Every rate change the user made in that window worked perfectly on the device
(`SET -> status=0`, `switching to 32000/44100`, eight in a row) yet appeared to fail in the app.

**This is a diagnosis trap worth remembering: a stolen stream is indistinguishable, from the app's
side, from a broken rate switch.** The app log and the device log each looked self-consistent; only
the two together showed the two client IPs. The app itself was never at fault — logs show it
following mid-stream rate changes correctly, rebuilding its AudioTrack at the new rate each time.

### 10.1 The rules
- A `CONFIGURE` / `HELLO` is accepted when the stream is free, when it comes from the current
  holder, or when the holder has been quiet for `TAKEOVER_IDLE_MS` (5 s). Otherwise it is refused
  and logged — a newcomer cannot take a stream that someone is actively using.
- `STOP` is honoured **only from the holder**. Previously any client could stop anyone's stream.
- Both listeners already heartbeat while they want audio (EASSP `DISCOVER` every 1.5 s from the
  app; a repeated `HELLO` from `spike_rx.py`, added for this). A heartbeat renews the lease.
- A holder silent for `LISTENER_DEAD_MS` (15 s) is dropped and streaming stops. The old firmware
  had a 15 s DISCOVER watchdog; v2 had no equivalent, which is why it never noticed.

### 10.2 Consequence for testing
Any tool that holds a stream must heartbeat, and must not be left running. When diagnosing an app
problem, **check the device log for a second client IP before believing anything else** — and
prefer taking app-side and device-side logs in the same window, with no other traffic.

### 9.7 `clkm_div` sets the internal clock — the intermittent rate-switch failure (2026-11-05)

A rate only fixes the **product** `bck_div * clkm_div`, but the factors are not interchangeable:
`clkm_div` divides the 160 MHz base to make the I2S clock and `bck_div` divides that to make BCK.
So a small `clkm_div` runs the peripheral's internal clock very fast.

88200 Hz is reachable two ways, both landing on exactly 87719.3 Hz:

| pair | internal clock | result |
|---|---|---|
| bck 19 / clkm 3 | **53 MHz** | capture dies at the switch, intermittently |
| bck 3 / clkm 19 | 8.4 MHz | fine |

The picker chose the 53 MHz pair **by floating-point accident**: `base/19/3` evaluates a hair closer
to the target than `base/3/19` does, because of division *order*, not because the rates differ.
Measured failure, on a healthy stream:

    45993  rate=44100 streaming=1 374 kbit/s  i2s_to=0   reinit=0
    46106  SET id=1 value=44100 -> status=0
    51004  frames frozen at 4280  i2s_to=122  0 kbit/s
           switching to 44100 ... reinit=1,2,3 ... never recovers

The stall detector cannot rescue this, because it re-initialises the **same** bad pair.

**Fix:** divide once by the product, so equal products compare equal, and keep only a strict
improvement while scanning `bck` ascending — the smallest `bck`, hence the largest `clkm`, hence
the slowest internal clock, wins every tie, deterministically. Plus `I2S_CLKM_MIN = 4`. Both rates
now use `bck = 3` (32 kHz: clkm 26, 6.2 MHz internal; 44.1 kHz: clkm 19, 8.4 MHz), keeping their
exact rates **and** their shared bit alignment from §9.5.

This was the user-reported "switching from one rate to the other sometimes fails". Note it was a
*genuine* fault that coexisted with the stream-stealing of §10 — two unrelated causes producing one
symptom, which is why neither explained every observation on its own.

### 9.8 Resolved: `dup` reads ~100 % at both rates
An earlier `dup=26%` reading at 44.1 kHz suggested the duplication structure might differ by rate.
It does not: on a healthy boot the counter reads **102-103 % at both 32 kHz and 44.1 kHz** (slightly
over 100 because a quiet signal adds chance matches beyond the half-of-positions denominator). The
low readings came from a boot with `i2s_to=1297, reinit=5` — the counter is cumulative since boot,
so bad reads early on dragged it down permanently. **Read `dup` only from a boot with `i2s_to` near
zero.**

### 9.9 The actual cause of the 44.1 kHz capture death: reconfiguring a running peripheral

§9.7 was necessary but not sufficient — with a perfectly sane `bck 3 / clkm 19` written, 44.1 kHz
capture still came up dead, on every retry:

    61267  switching to 44100 Hz
    61294  i2s 88200 Hz req -> bck_div=3 clkm_div=19, ws=87719 Hz
    62095  capture dead after switch (attempt 1) - retrying
    62925  capture dead after switch (attempt 2) - retrying
    63745  capture dead after switch (attempt 3) - retrying
    63747  rate switch to 44100 Hz did not come up

**Cause:** `i2s_driver_install()` ends with `i2s_set_clk(...)`, whose own comment is "set clock and
start". So the peripheral **and its DMA are already running** by the time control returns — on the
*driver's* dividers, which for an oversampled 44.1 kHz is `bck_div = 1`, i.e. no bit clock at all.
Writing new dividers underneath a peripheral that was started with a dead clock leaves it dead
permanently; a second `i2s_start()` does not rescue it. 32 kHz survived only because the driver's
own pick there, `bck_div = 2`, does clock, so the live reconfigure was benign.

**Fix:** `i2s_stop()` before writing the dividers, and let the following `i2s_start()` bring the
peripheral up on our dividers — it resets the FIFO, the SLC DMA rx/tx, and `rx_reset` as it goes.

**Lesson for this driver:** `i2s_driver_install` is not a configure-only call. Anything that touches
clock registers afterwards must stop the peripheral first.

### 9.10 Correction: the two client IPs
An earlier entry in this session blamed a stolen stream on the development Mac. **The Mac is
10.8.1.112; 10.8.1.194 is the phone.** The `CONFIGURE from 10.8.1.194` lines are the app
reconnecting after it stops getting audio — normal, correct behaviour — not a second client
stealing the stream. The ownership fix of §10 is still right and still worth having (a stray client
*can* steal a stream, and an unauthenticated `STOP` *can* kill one), but it was not the cause of
the rate-switch failure. **Identify an IP before building a theory on it.**

## 11. Latency: where the 250 ms actually was (2026-11-05)

The player reported a delay of "buffer + output", where *output* was `AudioTrack.bufferSizeInFrames`
— the app-side buffer only. The mixer and the HAL are not in that figure, so the reported number
was roughly 55 ms while the real delay was about 250 ms. `AudioTrack.getTimestamp()` closes the
gap: it says which frame is leaving the speaker and when, so the true depth below the app is
measurable. **Never estimate output latency from the buffer size.**

### 11.1 The dominant term was Android resampling
    device output: 48000 Hz, 192 frames/buffer (stream is 43860 Hz -> RESAMPLED, no fast path)

Android's low-latency fast mixer only takes a track at the device's **own** output rate and block
size. `PERFORMANCE_MODE_LOW_LATENCY` is a request, not a guarantee, and a mismatched rate silently
drops the track onto the resampling deep-buffer path. The microphone's rates come from the ESP's
integer dividers and cannot reach exactly 48000 (nearest reachable is 48077), so *something* must
resample — the only choice is whether it is us or AudioFlinger.

Doing it in the app (`Resampler.kt`, cubic Hermite, continuous across packets) took **output
latency from 110 ms to 57 ms**. It is interpolation rather than a brick-wall polyphase filter, which
is acceptable here on two grounds: the conversion was happening anyway with no control over it, and
its error sits ~35 dB below the microphone's own -61 dBFS noise floor (§2.4). Playback only —
nothing on the wire changes.

### 11.2 Startup underruns were permanently inflating the track buffer
`AudioTrack` always underruns once or twice while the pipeline fills. The playout loop treated any
underrun as evidence it needed a bigger buffer, so **every session** grew 16 ms -> 36 ms within a
second or two and stayed there. Ignoring the first second of playout leaves it at 16 ms with zero
growth events, while genuine steady-state underruns still grow it. Output: 57 ms -> 37 ms.

Sizing also matters: on the fast path the track buffer wants to be a small multiple of the device's
own block (4 x 192 frames = 16 ms), not a round number of milliseconds.

### 11.3 Measured results

| term | start | after |
|---|---|---|
| output path | 110 ms | 37 ms |
| ↳ track buffer | 49 | 16 |
| ↳ mixer + HAL | ~61 | ~21 (floor, not reducible) |
| jitter buffer | 7-109 (mean ~50) | 11-84, mostly 20-36 |
| **total measured** | **~160** | **~67** |

Trimming excess four times a second instead of once pulled the mean buffer depth from ~50 ms to
~28 without raising dropouts, because the trim is judged on the low-water mark either way.

### 11.4 What is left is arrival jitter, measured at the source
Receiver-side inter-arrival spacing, 10 ms frames, 2483 packets: mean **10.0 ms** (pacing is
exact), median 9.8, p90 14.4, p95 16.4, **p99 26.4**, worst 90.8; **4.8 % of packets arrive
back-to-back** (<2 ms). The Mac and the phone see the same bunching, so it is the transmit path or
the air, not phone-specific — and the phone's Wi-Fi lock was already correct (`WIFI_MODE_FULL_HIGH_PERF`
is the right mode at API 28; `WIFI_MODE_FULL_LOW_LATENCY` is the API 29+ replacement).

**So a jitter target below ~30 ms will underrun. The buffer is absorbing real jitter, not being
wasteful.**

### 11.5 FRAME_MS 10 -> 5: worked on jitter, cancelled by the target floor
| | 10 ms frames | 5 ms frames |
|---|---|---|
| stall duration, median | 33 ms | 25 ms |
| stall duration, p90 | 52 ms | 41 ms |
| dropouts per minute | ~29 | ~41 |

Shorter frames did shorten the stalls, as intended. But the player's target floor is
`largest packet + one block`, so halving the packet also halved the floor — the 10 ms setting's
effective target fell from 15 ms to 10 ms and the shallower buffer emptied more often. **The two
changes fought each other; this is not a clean A/B.** Keep the 5 ms frames and raise the target.

## 12. The packet loss was ours, not the air's (2026-11-05)

The player was concealing 8-25 lost packets a minute, which no amount of buffering could fix, and
which made the stream sound broken even with a 105 ms jitter buffer. It was local.

### 12.1 The measurement that located it
The device's own counters settle where the loss happens, without any guessing:

    frames - pkts - send_fail = 0        exactly, over 8 windows
    send_fail rate            = 0.263 % of frames, all errno 12 (ENOMEM)
    per 5 s window            = 6, 0, 3, 0, 11, 1, 0, 0   (bursty)

The first line proves nothing is dropped silently inside the firmware: every captured frame is
either sent or counted as a failure. The second accounts for essentially all the loss the player
was concealing — **so the air was contributing almost nothing**, and more jitter buffer was never
going to help.

### 12.2 The cause, and the SDK's own words
`CONFIG_ESP8266_WIFI_TX_PKT_NUM` was at 10 of a possible 16. The Kconfig help describes this exact
failure: *"For some applications especially UDP applications, the upper layer can deliver frames
faster than WiFi layer can transmit. In these cases, we may run out of TX packets."*

Raising it to 16 (~1.6 KB per buffer, ~9.6 KB more RAM; minimum free heap went 55.2 KB -> 51.2 KB):

| | TX_PKT_NUM=10 | =16 |
|---|---|---|
| ESP send failures | 0.263 % (21 / 8000) | **0.008 %** (1 / 13002) |
| app loss-concealment events | 8-25 per minute | 2 per 70 s |

**33x less loss from one configuration value, with no change to firmware logic.**

### 12.3 Knock-on: loss was inflating the measured jitter
A lost packet leaves a gap indistinguishable from a late one, so the player's auto target was being
sized against our own ENOMEM drops. After the fix auto settled from 102-108 ms down to 74 ms by
itself. **The earlier conclusion that "a jitter target below ~30 ms will underrun" (§11.4) was
measured on a link dropping 0.26 % locally and overstates the floor** - worth re-measuring the
arrival distribution now that the sender is not the main source of gaps.

## 13. AGC — implemented as a look-ahead limiter (2026-11-05)

§2 deferred AGC ("fixed gain is enough for now"). It is in, app-side, **off by default**, as
`Agc.kt` plus a Limiter (AGC) row in the PLAYBACK section of the device settings screen.

### 13.1 Why a limiter and not a compressor
The requirement was: minimal added latency, drop the volume *before* the signal overflows, recover
in 0.1-0.2 s. "Before it overflows" is the operative part, and it rules out any purely reactive
design - by the time a sample is over full scale it has already clipped. So: look-ahead.

**It subsumes the fixed gain.** The old path was `clamp(sample * gainX)`. A limiter placed after
that sees a signal that is already clipped, so `Agc.process()` takes the raw sample *and* the gain,
works in float, and saturates once at the end. This is the part that is easy to get wrong.

**2 ms of look-ahead**, which is the whole latency cost (against ~100 ms end to end, §11). Output
is delayed by that window and the gain is driven by the peak of the *coming* window, so the
reduction is in place before the peak arrives. The alternatives are clipping the first few samples
or stepping the gain, which clicks.

**Ceiling is -1.2 dBFS, not 0.** The resampler (§11.1) runs *after* the limiter, and cubic
interpolation overshoots between two in-range samples.

**Window peak is an O(1) upper bound** - peak of the block being filled plus the previous block,
covering one to two blocks - not an exact sliding maximum. It only over-estimates, so the error is
always towards slightly more reduction for slightly longer.

### 13.2 Measured, offline, before shipping it
Fixed gain x32 with a burst that would reach 64000 (2x full scale):

| | measured |
|---|---|
| peak output | 29605 — no clipping, 0 samples over full scale |
| gain 1 ms after onset | 0.632 (target 0.445) |
| gain 3 ms after onset | 0.454 |
| recovery to 0.95 | 122 ms |
| recovery to 0.99 | 203 ms |

Release time constant 0.05 s gives the requested 0.1-0.2 s recovery.

### 13.3 Known rough edge
Toggling the setting mid-stream calls `reset()` from the UI thread while the receive thread may be
mid-buffer. Arrays are fixed-size so it cannot crash, but a brief blip is audible at the moment of
the switch. Switching before playback avoids it. Worth a proper hand-off if AGC ever becomes a
thing users toggle often.

## 14. Compressed audio: measured options, and ADPCM (2026-11-06)

Uncompressed PCM is 538 kbit/s at 32 kHz and 727 at 44.1 (5 ms frames, EASSP header included).
That is fine on the LAN at 0.008 % loss (§12) but is the wrong size for a congested or long-haul
link. Measured on real captures from this microphone, not from tables:

| scheme | bits/sample | kbit/s @32k | kbit/s @44.1k | SNR vs PCM |
|---|---|---|---|---|
| PCM 16-bit | 16.00 | 513 | 702 | - |
| block float 12-bit | 12.05 | 386 | 528 | lossless |
| block float 10-bit | 10.05 | 322 | 440 | 65 / 60 dB |
| block float 8-bit | 8.05 | 258 | 352 | 47.8 dB |
| G.711 mu-law | 8.00 | 256 | 351 | 33.6 / 35.7 dB |
| IMA ADPCM 4-bit | 4.00 | 128 | 175 | 47.9 / 40.4 dB |
| lossless (2nd-order predict + Rice) | 4.90 / 7.11 | 157 | 312 | lossless |

**The headline: the wire signal only uses 11-12 of its 16 bits.** Since gain moved to the app
(§2.4) the raw samples peak at -33 dBFS in a quiet room and -24.8 dBFS in a normal one, so the top
four or five bits are sign extension. That is why every bit-depth scheme does so well here.

**mu-law is ruled out by measurement**, which is not what a table would tell you: 8 bits for worse
SNR than 4-bit ADPCM. It companding-optimises for signals using the full range, and ours sits
25-33 dB below it, so its codes are spent on levels we never reach.

**Lossless is genuinely viable** (3.3x on quiet material) because the room noise is strongly
low-frequency tilted, so a second-order predictor leaves small residuals. Variable bitrate is the
catch.

**Opus is not feasible on this part** - RAM and no FPU. That is an ESP32-class change.

### 14.1 What was implemented: IMA ADPCM
Chosen for the largest fixed reduction, and because the Android decoder already existed
(`Eassp.AdpcmDecoder`, DVI4 layout) from the old firmware and had simply never been exercised.
End to end, including the 16-byte EASSP header: **727 -> 208 kbit/s at 44.1 kHz, 538 -> 160 at
32 kHz**, a 3.5x and 3.4x reduction.

It is **audibly lossy**: 40.4 dB SNR at 44.1 and 47.9 at 32, against the microphone's own 61 dB.
Worst on noise-like material, which is what high playback gain makes audible. It is the trade to
make when the link cannot carry PCM, not an upgrade. Note 32 kHz is both the lower bitrate *and*
the higher SNR of the two, because the quieter capture is easier for the predictor.

**Every packet carries the predictor and step index it starts from.** The encoder keeps running
state across packets and writes it into each header, so a lost packet costs exactly that packet
and the next resynchronises - no drift, and none of the 200 Hz discontinuity that resetting to
zero every 5 ms would cause. Given §12's work to get loss to 0.008 %, statelessness per packet was
worth more than the handful of bits it costs.

Only the EASSP (app) path is compressed; the native header has no codec field, so `spike_rx.py`
always receives PCM and stays a known-good uncompressed reference.

### 14.2 Protocol: settings now carry per-value labels
A codec dropdown showing "5" and "6" is useless, so SETTINGS gained a label per allowed value.
Putting that mapping in the app instead would have been exactly the duplication §8.2 exists to
prevent - the device names its own values, and a new setting still needs no app change.
