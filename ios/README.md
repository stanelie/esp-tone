# ToneMic — iOS client

A SwiftUI client for the ESP8266 microphone. The protocol details it relies on are documented in
`../SPEC.md`; this file is only about building and deploying it.

## Build and run on the phone

```bash
cd ios/ToneMic
xcodebuild -project ToneMic.xcodeproj -scheme ToneMic -configuration Debug \
  -destination 'platform=iOS,id=<device-udid>' \
  -derivedDataPath build -allowProvisioningUpdates build
xcrun devicectl device install app --device <device-udid> \
  build/Build/Products/Debug-iphoneos/ToneMic.app
```

`xcodebuild -showdestinations -scheme ToneMic` lists the UDID. Note it is **not** the identifier
`devicectl list devices` prints first — that is a CoreDevice id, and xcodebuild rejects it.

To see the app's own diagnostics:

```bash
xcrun devicectl device process launch --device <udid> --console ca.exmachina.tonemic
```

## Adding a source file

Run `../gen_project.py`. An `.xcodeproj` enumerates its sources explicitly, so a new file is
invisible until the project is regenerated — the symptom is `cannot find type X in scope` for a
type that plainly exists.

## Things that cost time, so they are written down

- **`print()` is invisible under `--console`** unless stdout is unbuffered. `ToneMicApp.init()`
  calls `setvbuf(stdout, nil, _IONBF, 0)`.
- **`NWListener` is the wrong tool for this.** The first receive path used one on an ephemeral
  port. Audio arrived — the log showed `inbound connection from 10.8.1.168:3950` repeatedly — but
  a new connection was spawned per datagram and none lived long enough to deliver a message. One
  `NWConnection` bound to a known local port, used for both CONFIGURE and audio, is simpler and
  fits the protocol: the port you nominate is the port you listen on.
- **Discovery is a unicast sweep, not a broadcast.** Broadcast and multicast on iOS need
  `com.apple.developer.networking.multicast`, which Apple grants by request. Unicast needs only
  the local-network permission.
- **`NSLocalNetworkUsageDescription` is mandatory.** Without it iOS kills the app on its first
  local packet instead of prompting.
- **The device serves one listener at a time.** If the Android client is connected, the iPhone's
  CONFIGURE is refused and it looks exactly like a broken client. Stop the other listener first.

## Verifying the DSP without a device

`Filters.swift` and `Agc.swift` depend only on Foundation, so they compile for macOS and can be
measured directly — no simulator, no device, no test target:

```bash
swiftc -O Sources/Filters.swift Sources/Agc.swift main.swift -o dsptest && ./dsptest
```

(top-level code has to live in a file called `main.swift`). The port was checked this way against
the Android figures: low cut 250 Hz gives −3.0 dB at the corner and −12.3 dB an octave below, high
cut 8 kHz gives −3.0 dB at 8 kHz, and the limiter takes an input reaching 2× full scale down to a
peak of 29606 with no samples over. Identical to the Android implementation.

## Verifying the jitter buffer without a device

`Concealer.swift` and `JitterBuffer.swift` are also Foundation-only, so the same `swiftc` trick
works. Push a tone, pull blocks, then stop pushing and watch what comes out:

- concealment: RMS during the gap should stay near the normal playout level, not fall to zero
- trimming: with push and pull **balanced** so the test is not quietly draining the buffer, the
  depth should fall to the target while the worst sample-to-sample step stays at what the test
  tone does naturally (0.0105 for a 300 Hz sine at 8000/32768)

Both caught real bugs: the first version mixed the concealer's full-scale units into the
normalised output (worst step 7975 instead of 0.0105), and the first trimming test pushed fewer
frames than it pulled, so the drain looked like trimming.

## @Published fires didSet inside init

Measured, because it is the opposite of what the Swift book implies:

```swift
final class Probe {
    @Published var a = 1 { didSet { print("a") } }   // fires during init
    var plain = 3        { didSet { print("plain") } } // does not
    init() { a = 10; plain = 30 }                      // prints "a" only
}
```

"Observers are not called during initialization" holds for a plain stored property. A `@Published`
one assigns through the wrapper's setter, so `didSet` runs.

This destroyed the playback-settings persistence. Loading `gainDb` in `init` fired its `didSet`,
which called `savePrefs()`, which writes *every* property — and the rest were still at their
defaults, so the stored buffer, cutoffs and limiter were overwritten before the next line could
read them. Gain survived; nothing after it did. The fix is a `loaded` flag that suppresses saving
until `init` has finished.

Two wrong guesses preceded the measurement (a `UserDefaults` batching theory, and a `synchronize()`
"fix"), each costing a build-and-install cycle. The 20-line probe above took a minute.
