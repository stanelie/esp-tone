"""EASSP device simulator for the settings screen: answers DISCOVER with INFO and runs AT commands
sent over the network (cmd 0x04 -> reply 0x84), mimicking the firmware's reply texts. No audio.
Repeats the stored answer for a resent sequence number, like the firmware."""
import socket, struct

PORT = 3950
MAC = bytes([0x02, 0x00, 0x00, 0xEA, 0x55, 0x03])
cfg = {"RATE": "48000", "BITS": "24", "CODEC": "1", "CH": "0", "GAIN": "32", "AGC": "0", "FMT": "0",
       "XPORT": "0", "TXPWR": "20", "HOST": "mock-at", "TIMING": "0,0,0", "SSID": "my-network"}
log = []

def query(k):
    v = cfg[k]
    if k == "TIMING":
        return "+TIMING:sd={},ws={},bck={}".format(*v.split(","))
    return {"HOST": f'+HOST:"{v}"', "TXPWR": f"+TXPWR:{v} dBm (max 20)", "CH": f"+CH:{v} (left, 1 ch)",
            "AGC": f"+AGC:{v} (OFF, attack=0, release=0)", "XPORT": f"+XPORT:{v} (UDP)",
            "CODEC": f"+CODEC:{v} (PCM - lossless)", "FMT": f"+FMT:{v} (Philips)"}.get(k, f"+{k}:{v}")

def run(line):
    log.append(line)
    if line == "AT+STATUS":
        return "".join(f"+STATUS:{k.lower()}={v}\r\n" for k, v in cfg.items()) + "\r\nOK\r\n"
    if line == "AT+HOTRESTART":
        return "+ERR:HOTRESTART:stream not active - params saved, will apply on next start\r\n\r\nERROR\r\n"
    if line == "AT+WIFI?":
        return f'+WIFI:ssid="{cfg["SSID"]}"\r\n+WIFI:password=<12 chars, hidden>\r\n\r\nOK\r\n'
    if line.startswith("AT+WIFI="):
        ssid = line[8:].split(",")[0].strip('"'); cfg["SSID"] = ssid
        return f'+WIFI:set to ssid="{ssid}" (saved, reconnecting)\r\n\r\nOK\r\n'
    if line.startswith("AT+") and line.endswith("?") and line[3:-1] in cfg:
        return query(line[3:-1]) + "\r\n\r\nOK\r\n"
    if line.startswith("AT+") and "=" in line:
        k, v = line[3:].split("=", 1)
        if k == "BITS" and v not in ("16", "24"):
            return "+ERR:bits must be 16 or 24\r\n\r\nERROR\r\n"
        if k in cfg:
            cfg[k] = v
            return f"+{k}:set to {v} (saved, use AT+HOTRESTART to apply)\r\n\r\nOK\r\n"
    return f'+ERR:unknown command "{line}"\r\n\r\nERROR\r\n'

def info(seq):
    p = struct.pack("<BBBBIB6sIIb8sBB24s", 0, 6, 0, 1, int(cfg["RATE"]), 5, MAC, 0, 30000, -50,
                    b"mock", int(cfg["BITS"]), 0, cfg["HOST"].encode())
    return struct.pack("<2sBBHH", b"\xea\x55", 1, 0x81, seq, len(p)) + p

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(("0.0.0.0", PORT))
last = None   # (seq, src, reply)
print("mock AT device on UDP", PORT, flush=True)
while True:
    data, src = sock.recvfrom(512)
    if len(data) < 8 or data[:3] != b"\xea\x55\x01":
        continue
    cmd, seq, plen = data[3], *struct.unpack_from("<HH", data, 4)
    if cmd == 1:
        sock.sendto(info(seq), src)
    elif cmd == 4:
        if last and last[0] == seq and last[1] == src:
            reply = last[2]
        else:
            line = data[8:8 + plen].decode()
            reply = run(line).encode()
            print("AT:", line, "->", reply.decode().strip().splitlines()[0], flush=True)
            last = (seq, src, reply)
        sock.sendto(struct.pack("<2sBBHH", b"\xea\x55", 1, 0x84, seq, len(reply)) + reply, src)
