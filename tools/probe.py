"""Asks an EASSP device for INFO (unicast DISCOVER) and runs AT+STATUS over the network; also
listens 6 s for its idle INFO broadcasts on port 3950."""
import socket, struct, sys, time

host = sys.argv[1] if len(sys.argv) > 1 else "10.8.1.168"
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.settimeout(0.5)

def ask(cmd, payload=b"", seq=7):
    pkt = struct.pack("<2sBBHH", b"\xea\x55", 1, cmd, seq, len(payload)) + payload
    for _ in range(4):
        s.sendto(pkt, (host, 3950))
        try:
            while True:
                data, src = s.recvfrom(2048)
                if src[0] == host:
                    return data
        except socket.timeout:
            continue
    return None

r = ask(0x01)
print("DISCOVER ->", "no answer" if r is None else f"cmd 0x{r[3]:02x}, {len(r)} bytes, status={r[8]}")
r = ask(0x04, b"AT+STATUS", seq=1234)
print("AT+STATUS ->", "no answer" if r is None else r[8:].decode(errors="replace"))

b = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
b.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
b.bind(("0.0.0.0", 3950))
b.settimeout(0.5)
end, seen = time.time() + 6, 0
while time.time() < end:
    try:
        data, src = b.recvfrom(2048)
        if src[0] == host:
            seen += 1
    except socket.timeout:
        pass
print("broadcast INFO packets heard in 6 s:", seen)
