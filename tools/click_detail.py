import sys
import numpy as np
x = np.frombuffer(open(sys.argv[1], "rb").read(), dtype="<i2").astype(np.int64)
rate = 43860
d2 = np.abs(np.diff(x, 2))
idx = np.where(d2[rate:] > 3000)[0] + rate
ev = [idx[0]] + [b for a, b in zip(idx, idx[1:]) if b - a > rate // 200]
print("first events (s):", [round(e / rate, 4) for e in ev[:12]])
gaps = np.diff(ev)
print("spacing between events (ms):", [round(g * 1000 / rate, 1) for g in gaps[:20]])
for e in ev[:3]:
    print(f"--- around {e} ({e/rate:.4f} s), block phase {e % 219}")
    print(x[e - 6:e + 8].tolist())
