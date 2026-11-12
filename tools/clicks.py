"""Finds clicks in a capture of the app's output (16-bit LE mono): the test chord is smooth, so its
second difference stays tiny (under ~40); a seam that doesn't match shows up as a spike."""
import struct, sys
import numpy as np

path, rate = sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 43860
x = np.frombuffer(open(path, "rb").read(), dtype="<i2").astype(np.float64)
x = x[rate:]                                   # skip the first second (startup)
d2 = np.abs(np.diff(x, 2))
print(f"{len(x)/rate:.1f} s, peak level {np.abs(x).max():.0f}, smooth-signal d2 p99.9 = {np.percentile(d2, 99.9):.0f}")
for thr in (100, 300, 1000, 3000):
    idx = np.where(d2 > thr)[0]
    # count separate events (spikes closer than 5 ms are one event)
    events = 0 if len(idx) == 0 else 1 + int(np.sum(np.diff(idx) > rate // 200))
    print(f"  d2 > {thr:5d}: {events} events")
# silence (faded-out concealment): runs of 5 ms or more under 1% of peak
quiet = np.abs(x) < 0.01 * np.abs(x).max()
runs, n = 0, 0
for q in quiet[::10]:
    n = n + 1 if q else 0
    if n == rate // 2000: runs += 1
print(f"  near-silent stretches >= 5 ms: {runs}")
