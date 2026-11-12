#!/usr/bin/env python3
"""Read and change the v2 device's settings over the self-describing settings protocol.

The device reports what exists, what values are allowed, whether each is applicable and when it
takes effect; nothing here hardcodes any of that (SPEC.md 8.2). Also serves as the reference for
the Android implementation.

  tools/spike_settings.py 10.8.1.168                 # list
  tools/spike_settings.py 10.8.1.168 --set 1=44100   # change
"""
import argparse, socket, struct, sys

PORT = 3950
M0, M1 = 0xE7, 0x03
GET, SETTINGS, SET, RESULT = 1, 2, 3, 4
KIND = {1: "enum", 2: "range"}
WHEN = {0: "immediately", 1: "on stream restart", 2: "on reboot"}
STATUS = {0: "ok", 1: "unknown setting", 2: "value not allowed", 3: "not applicable now"}

def parse_settings(d):
    if len(d) < 4 or d[0] != M0 or d[1] != M1 or d[2] != SETTINGS:
        raise ValueError("not a SETTINGS reply")
    o, out = 4, []
    for _ in range(d[3]):
        sid, kind, applicable, when = d[o], d[o+1], d[o+2], d[o+3]; o += 4
        cur = struct.unpack_from("<I", d, o)[0]; o += 4
        n = d[o]; o += 1
        vals = [struct.unpack_from("<I", d, o + 4*i)[0] for i in range(n)]; o += 4*n
        # Per-value labels: the device names its own values, because "5" and "6" mean nothing.
        vlabels = []
        for _ in range(n):
            vl = d[o]; o += 1
            vlabels.append(d[o:o+vl].decode(errors="replace")); o += vl
        ln = d[o]; o += 1
        label = d[o:o+ln].decode(errors="replace"); o += ln
        out.append(dict(id=sid, kind=kind, applicable=applicable, when=when,
                        current=cur, values=vals, vlabels=vlabels, label=label))
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("host")
    ap.add_argument("--set", metavar="ID=VALUE")
    a = ap.parse_args()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(2.0)

    if a.set:
        sid, val = a.set.split("=", 1)
        s.sendto(bytes([M0, M1, SET, int(sid)]) + struct.pack("<I", int(val)), (a.host, PORT))
        try:
            d, _ = s.recvfrom(512)
        except socket.timeout:
            print("no reply"); return 1
        if len(d) >= 9 and d[2] == RESULT:
            cur = struct.unpack_from("<I", d, 5)[0]
            print("set id=%d -> %s (now %d)" % (d[3], STATUS.get(d[4], "?%d" % d[4]), cur))
            if d[4] != 0:
                return 1
        return 0

    for attempt in range(3):
        s.sendto(bytes([M0, M1, GET]), (a.host, PORT))
        try:
            d, _ = s.recvfrom(1024)
        except socket.timeout:
            continue
        try:
            items = parse_settings(d)
        except ValueError:
            continue    # could be an audio packet if a stream is running; try again
        print("%-14s %-6s %-10s %-20s %s" % ("SETTING", "ID", "CURRENT", "ALLOWED", "APPLIES"))
        for it in items:
            allowed = ",".join(str(v) for v in it["values"]) if it["kind"] == 1 \
                      else "%d..%d" % (it["values"][0], it["values"][1])
            print("%-14s %-6d %-10d %-20s %s%s" % (
                it["label"], it["id"], it["current"], allowed,
                WHEN.get(it["when"], "?"),
                "" if it["applicable"] else "   [NOT APPLICABLE NOW]"))
        return 0
    print("no SETTINGS reply"); return 1

if __name__ == "__main__":
    sys.exit(main())
