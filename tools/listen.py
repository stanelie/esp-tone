"""Lists every EASSP device heard on the LAN for 8 s: idle INFO broadcasts to port 3950, and answers
to a broadcast DISCOVER."""
import socket, struct, time

b = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
b.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
b.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
b.bind(("0.0.0.0", 3950))
b.settimeout(0.5)
q = struct.pack("<2sBBHH", b"\xea\x55", 1, 0x01, 0, 0)
found, end, last_q = {}, time.time() + 8, 0
while time.time() < end:
    if time.time() - last_q > 2:
        for dest in ("10.8.1.255", "255.255.255.255"):
            b.sendto(q, (dest, 3950))
        last_q = time.time()
    try:
        data, src = b.recvfrom(2048)
    except socket.timeout:
        continue
    if len(data) >= 8 and data[:2] == b"\xea\x55" and data[3] == 0x81:
        name = data[8 + 34:8 + 58].split(b"\0")[0].decode(errors="replace") if len(data) >= 66 else "?"
        mac = data[8 + 9:8 + 15].hex()
        found.setdefault(src[0], (name, mac))
for ip, (name, mac) in found.items():
    print(ip, name, mac)
print(len(found), "device(s)")
