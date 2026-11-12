"""EASSP device simulator with network trouble: streams 16-bit PCM (a chord with vibrato) by UDP,
paced at the ESP's real I2S rate, dropping LOSS of the packets and stalling STALL_MS every STALL_EVERY s
(the held packets then arrive in a burst, as after a Wi-Fi stall).
Usage: python mock_eassp2.py [rate] [loss] [stall_every_s] [stall_ms]"""
import math, random, socket, struct, sys, threading, time

PORT = 3950
NOMINAL = int(sys.argv[1]) if len(sys.argv) > 1 else 44100
LOSS = float(sys.argv[2]) if len(sys.argv) > 2 else 0.02
STALL_EVERY = float(sys.argv[3]) if len(sys.argv) > 3 else 5
STALL_MS = float(sys.argv[4]) if len(sys.argv) > 4 else 150
BITS, CH, FRAME_MS = 16, 1, 5
RATE_ENUM = {8000: 0, 11025: 1, 16000: 2, 22050: 3, 32000: 4, 44100: 5, 48000: 6}[NOMINAL]
MAC = bytes([0x02, 0x00, 0x00, 0xEA, 0x55, 0x02])

def real_rate(nominal, bits):
    base = 160e6 / (32 if bits == 16 else 48)
    best, rate = 1e30, nominal
    for i in range(1, 64):
        for j in range(i, 64):
            r = base / i / j
            if abs(r - nominal) < best: best, rate = abs(r - nominal), r
    return rate

RATE = real_rate(NOMINAL, BITS)
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(("0.0.0.0", PORT))
dest, last_hb, lock = None, 0.0, threading.Lock()

def info(seq, status):
    p = struct.pack("<BBBBIB6sIIb8sBB24s", status, 6, 0, CH, NOMINAL, FRAME_MS, MAC, 0, 30000, -50,
                    b"mock2", BITS, 0, b"mock-mic2")
    return struct.pack("<2sBBHH", b"\xea\x55", 1, 0x81, seq, len(p)) + p

def streamer():
    global dest
    out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    n = round(RATE * FRAME_MS / 1000)
    seq, t, t_next = 0, 0, time.monotonic()
    held, stall_until, next_stall = [], 0.0, time.monotonic() + STALL_EVERY
    sent = lost = 0
    while True:
        with lock:
            d = dest
            if d and time.monotonic() - last_hb > 15:
                print("watchdog: no heartbeat, stopping"); dest = d = None
        if not d:
            time.sleep(0.05); t_next = time.monotonic(); continue
        buf = bytearray()
        for _ in range(n):
            x = t / RATE
            v = sum(math.sin(2 * math.pi * f * x + 0.002 * f * math.sin(2 * math.pi * 5 * x)) for f in (220, 277.2, 329.6))
            buf += struct.pack("<h", int(v * 0.25 * 32767 / 3 * 3))
            t += 1
        hdr = struct.pack("<HIBBBBIH", seq & 0xFFFF, int(time.monotonic() * 1000) & 0xFFFFFFFF, 6, RATE_ENUM, CH,
                          FRAME_MS, NOMINAL * BITS * CH, BITS)
        seq += 1
        now = time.monotonic()
        if STALL_EVERY > 0 and now >= next_stall:
            stall_until, next_stall = now + STALL_MS / 1000, now + STALL_EVERY
        if random.random() < LOSS:
            lost += 1                       # lost on the air: the receiver sees a sequence gap
        elif now < stall_until:
            held.append(hdr + buf)          # queued in the AP
        else:
            for p in held: out.sendto(p, d)
            held.clear()
            out.sendto(hdr + buf, d)
        sent += 1
        if sent % 2000 == 0: print(f"sent {sent}, lost {lost}")
        t_next += n / RATE
        time.sleep(max(0, t_next - time.monotonic()))

threading.Thread(target=streamer, daemon=True).start()
print(f"mock EASSP mic on UDP {PORT}: {NOMINAL} Hz (really {RATE:.0f}), loss {LOSS}, stall {STALL_MS} ms every {STALL_EVERY} s")
while True:
    data, src = sock.recvfrom(512)
    if len(data) < 8 or data[:3] != b"\xea\x55\x01":
        continue
    cmd, seq = data[3], struct.unpack_from("<H", data, 4)[0]
    with lock:
        if cmd == 1:
            last_hb = time.monotonic()
            sock.sendto(info(seq, 1 if dest else 0), src)
        elif cmd == 2:
            port = struct.unpack_from(">H", data, 8)[0]
            dest = (src[0], port); last_hb = time.monotonic()
            print("CONFIGURE ->", dest)
            sock.sendto(info(seq, 1), src)
        elif cmd == 3:
            print("STOP from", src); dest = None
            sock.sendto(info(seq, 0), src)
