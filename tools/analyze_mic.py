"""Record N seconds from the esp-mic HTTP stream and look for what kind of glitches it has.

Bit errors on the I2S wires show up as isolated spikes (one sample far from both neighbours).
Lost samples (buffer overruns) show up as steps/discontinuities and a lower-than-expected count.
"""
import socket, struct, sys, time, wave

host = sys.argv[1] if len(sys.argv) > 1 else "10.8.1.168"
secs = float(sys.argv[2]) if len(sys.argv) > 2 else 10
out = sys.argv[3] if len(sys.argv) > 3 else "mic.wav"

s = socket.create_connection((host, 1234), timeout=5)
s.sendall(b"GET / HTTP/1.0\r\n\r\n")
buf = b""
while b"\r\n\r\n" not in buf:
    buf += s.recv(4096)
head, rest = buf.split(b"\r\n\r\n", 1)
print(head.split(b"\r\n")[0].decode())
data = bytearray(rest)
t0 = time.monotonic()
while time.monotonic() - t0 < secs:
    chunk = s.recv(65536)
    if not chunk:
        break
    data += chunk
elapsed = time.monotonic() - t0
s.close()

hdr, pcm = data[:44], data[44:]
rate = struct.unpack_from("<I", hdr, 24)[0]
n = len(pcm) // 2
x = struct.unpack("<%dh" % n, pcm[: n * 2])
with wave.open(out, "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(bytes(pcm[: n * 2]))

print(f"received {n} samples in {elapsed:.2f} s = {n / elapsed:.0f} samples/s (header says {rate})")
skip = rate // 2          # ignore the start-up transient
y = x[skip:]
peak = max(abs(v) for v in y)
mean_abs = sum(abs(v) for v in y) / len(y)
print(f"peak {peak}, mean |x| {mean_abs:.0f}")

# Spikes: a sample far from the average of its neighbours, while the neighbours agree with each other.
spikes = []
for i in range(1, len(y) - 1):
    a, b, c = y[i - 1], y[i], y[i + 1]
    d = abs(b - (a + c) / 2)
    if d > 2000 and d > 6 * abs(a - c) + 500:
        spikes.append((i + skip, b, a, c))
print(f"isolated spikes: {len(spikes)}  ({len(spikes) / (len(y) / rate):.1f}/s)")
for i, b, a, c in spikes[:15]:
    print(f"  @{i} ({i / rate:.3f}s): {a} -> {b} -> {c}   value hex {b & 0xFFFF:04x}")

# Bit patterns of spike values: errors in one bit position show as a fixed power of two.
if spikes:
    diffs = {}
    for i, b, a, c in spikes:
        e = (b - (a + c) // 2) & 0xFFFF
        top = e.bit_length()
        diffs[top] = diffs.get(top, 0) + 1
    print("error size by highest bit:", dict(sorted(diffs.items())))

# Steps: large jumps that are not single spikes.
steps = sum(1 for i in range(1, len(y)) if abs(y[i] - y[i - 1]) > 4000)
print(f"large jumps (>4000) between consecutive samples: {steps}")
