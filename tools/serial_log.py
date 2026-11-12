"""Logs the ESP's serial output (115200) to a file with PC timestamps, without resetting it
(DTR/RTS held inactive). Optionally sends one AT line first. Usage: serial_log.py PORT OUT [AT-line]"""
import sys, time, serial

port, out = sys.argv[1], sys.argv[2]
s = serial.Serial()
s.port, s.baudrate, s.timeout = port, 115200, 0.2
s.dtr = False
s.rts = False
s.open()
if len(sys.argv) > 3:
    s.write((sys.argv[3] + "\r\n").encode())
with open(out, "a", encoding="utf-8", errors="replace") as f:
    buf = b""
    while True:
        buf += s.read(512)
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            f.write(time.strftime("%H:%M:%S ") + line.decode(errors="replace").rstrip("\r") + "\n")
            f.flush()
