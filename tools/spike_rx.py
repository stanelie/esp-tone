#!/usr/bin/env python3
"""Receiver/measurement harness for the firmware-v2 throughput spike.

Sends HELLO to the ESP, receives the audio stream, and reports sustained bitrate and packet loss.
Optionally writes a WAV so the 32 kHz vs 44.1 kHz question (SPEC.md 4.1) can be judged by ear.

  tools/spike_rx.py 10.8.1.168 --seconds 60 --wav /tmp/spike.wav
  tools/spike_rx.py 10.8.1.168 --rate 44        # switch the mic to 44.1 kHz first
"""
import argparse, socket, struct, sys, time, wave

CTRL_PORT = 3950
NOMINAL = {0: 32000, 1: 44100}

def actual_rate(nominal, bits=16):
    """The ESP's real I2S rate: 160 MHz through two integer dividers (SPEC.md 4.2),
    so 44100 is really ~43860. Needed or the WAV plays at the wrong pitch."""
    base = 160_000_000 / (32 if bits == 16 else 48)
    best, rate = float("inf"), float(nominal)
    for i in range(1, 64):
        for j in range(i, 64):
            r = base / i / j
            if abs(r - nominal) < best:
                best, rate = abs(r - nominal), r
    return int(round(rate))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("host")
    ap.add_argument("--seconds", type=int, default=60)
    ap.add_argument("--wav")
    ap.add_argument("--rate", choices=["32", "44"], help="switch rate before measuring")
    ap.add_argument("--bits", choices=["16", "24"], help="switch CAPTURE width before measuring")
    ap.add_argument("--gain", type=int, default=32,
                    help="gain applied when writing the WAV (32 = +30 dB). Applied here, not on "
                         "the device, so the captured data stays pristine (SPEC.md 2.4).")
    a = ap.parse_args()

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(1.0)
    s.bind(("", 0))
    dst = (a.host, CTRL_PORT)

    if a.bits:
        s.sendto(b"B" + a.bits.encode(), dst)
        print("sent B%s (capture width), waiting for the I2S restart..." % a.bits)
        time.sleep(2.5)
    if a.rate:
        s.sendto(b"R" + a.rate.encode(), dst)
        print("sent R%s, waiting for the I2S restart..." % a.rate)
        time.sleep(2.5)

    s.sendto(b"HELLO", dst)
    print("HELLO -> %s:%d, measuring for %ds (ctrl-c to stop)" % (a.host, CTRL_PORT, a.seconds))
    # The device drops a listener that goes quiet (LISTENER_DEAD_MS), so repeat HELLO as a
    # heartbeat - it both renews our lease and stops us being mistaken for a departed client.
    last_hb = time.time()

    pkts = bytes_rx = lost = reorder = 0
    expect = None
    cap_bits = 0
    actual = 0
    pcm = bytearray()
    t0 = time.time()
    win_t, win_p, win_b, win_lost = t0, 0, 0, 0

    try:
        while time.time() - t0 < a.seconds:
            try:
                d, _ = s.recvfrom(2048)
            except socket.timeout:
                print("  ... no data (is it streaming? did HELLO arrive?)")
                s.sendto(b"HELLO", dst)
                continue
            if len(d) < 12 or d[0] != 0xE7 or d[1] != 0x02:
                continue
            cap_bits = d[2]
            seq = struct.unpack_from("<I", d, 4)[0]
            # The device sends its real hardware rate, so nothing here infers it.
            actual = struct.unpack_from("<I", d, 8)[0]
            pkts += 1; win_p += 1
            bytes_rx += len(d); win_b += len(d)
            if expect is not None:
                if seq > expect:
                    gap = seq - expect
                    lost += gap; win_lost += gap
                elif seq < expect:
                    reorder += 1
            expect = seq + 1
            if a.wav:
                pcm += d[12:]

            now = time.time()
            if now - last_hb >= 2.0:
                s.sendto(b"HELLO", dst)
                last_hb = now
            if now - win_t >= 5.0:
                dt = now - win_t
                kbps = win_b * 8 / dt / 1000.0
                tot = win_p + win_lost
                lossp = (100.0 * win_lost / tot) if tot else 0.0
                print("  %5.1fs  %6.1f kbit/s  %4d pkt/s  loss %5.2f%%  (lost %d)"
                      % (now - t0, kbps, win_p / dt, lossp, win_lost))
                win_t, win_p, win_b, win_lost = now, 0, 0, 0
    except KeyboardInterrupt:
        print("\ninterrupted")

    s.sendto(b"STOP", dst)
    dt = max(time.time() - t0, 0.001)
    total = pkts + lost
    print("\n=== summary ===")
    print("duration      : %.1f s" % dt)
    print("packets       : %d (lost %d, reordered %d)" % (pkts, lost, reorder))
    print("loss          : %.3f %%" % (100.0 * lost / total if total else 0.0))
    print("mean bitrate  : %.1f kbit/s" % (bytes_rx * 8 / dt / 1000.0))
    if actual:
        print("capture width : %d-bit  (transmitted 16-bit)" % cap_bits)
        print("sample rate   : %d actual (reported by the device)" % actual)
    if a.wav and pcm:
        sr_out = actual or 32051
        if a.gain and a.gain != 1:
            import array
            sm = array.array("h"); sm.frombytes(bytes(pcm[: len(pcm) // 2 * 2]))
            for i, v in enumerate(sm):
                v *= a.gain
                sm[i] = 32767 if v > 32767 else (-32768 if v < -32768 else v)
            pcm = bytearray(sm.tobytes())
            print("applied x%d gain to the WAV" % a.gain)
        with wave.open(a.wav, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr_out)
            w.writeframes(bytes(pcm))
        print("wrote         : %s (%.1f s of audio)" % (a.wav, len(pcm) / 2.0 / sr_out))

if __name__ == "__main__":
    main()
