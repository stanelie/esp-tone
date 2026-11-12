"""Minimal EASSP device: answers DISCOVER with INFO, streams 48 kHz 24-bit PCM (a 440 Hz tone) by UDP after CONFIGURE."""
import math, socket, struct, threading, time

PORT = 3950
RATE, BITS, CH = 48000, 24, 1
FRAME_MS = 5
MAC = bytes([0x02, 0x00, 0x00, 0xEA, 0x55, 0x01])

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(("0.0.0.0", PORT))

dest = None          # (ip, port) for audio
last_hb = 0.0
lock = threading.Lock()

def info(seq, status):
    p = struct.pack("<BBBBIB6sIIb8sBB24s", status, 6, 0, CH, RATE, FRAME_MS, MAC, 0, 30000, -50,
                    b"mock", BITS, 0, b"mock-mic")
    return struct.pack("<2sBBHH", b"\xea\x55", 1, 0x81, seq, len(p)) + p

def streamer():
    global dest
    out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    n = RATE * FRAME_MS // 1000
    seq, phase, t_next = 0, 0.0, time.monotonic()
    while True:
        with lock:
            d = dest
            if d and time.monotonic() - last_hb > 15:
                print("watchdog: no heartbeat, stopping"); dest = d = None
        if not d:
            time.sleep(0.05); t_next = time.monotonic(); continue
        samples = bytearray()
        for _ in range(n):
            v = int(math.sin(phase) * 0.3 * 8388607)
            phase += 2 * math.pi * 440 / RATE
            samples += struct.pack("<i", v)[:3]
        hdr = struct.pack("<HIBBBBIH", seq & 0xFFFF, int(time.monotonic() * 1000) & 0xFFFFFFFF, 6, 6, CH,
                          FRAME_MS, RATE * BITS * CH, BITS)
        out.sendto(hdr + samples, d)
        seq += 1
        t_next += FRAME_MS / 1000
        time.sleep(max(0, t_next - time.monotonic()))

threading.Thread(target=streamer, daemon=True).start()
print("mock EASSP mic on UDP", PORT)
while True:
    data, src = sock.recvfrom(512)
    if len(data) < 8 or data[:3] != b"\xea\x55\x01":
        continue
    cmd, seq = data[3], struct.unpack_from("<H", data, 4)[0]
    with lock:
        if cmd == 1:
            last_hb = time.monotonic()
            print("DISCOVER from", src)
            sock.sendto(info(seq, 1 if dest else 0), src)
        elif cmd == 2:
            port = struct.unpack_from(">H", data, 8)[0]
            dest = (src[0], port); last_hb = time.monotonic()
            print("CONFIGURE ->", dest)
            sock.sendto(info(seq, 1), src)
        elif cmd == 3:
            print("STOP from", src); dest = None
            sock.sendto(info(seq, 0), src)
