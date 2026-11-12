"""Merges the mic firmware's four images into esp-mic-merged.bin, to flash at 0x0 on a blank chip
(gaps filled with 0xFF). This ERASES the settings partition (NVS, 0x9000-0xEFFF); for an update,
flash only esp-mic-app.bin at 0x10000 instead."""
import os

d = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ESP8266-WiFi-MICROPHONE", "build-esp01")
parts = [(0x0, "bootloader.bin"), (0x8000, "partition-table.bin"), (0xF000, "phy_init_data.bin"),
         (0x10000, "esp-adpcm-streamer.bin")]
out = bytearray()
for off, name in parts:
    data = open(os.path.join(d, name), "rb").read()
    assert len(out) <= off, f"{name} overlaps the previous image"
    out += b"\xff" * (off - len(out)) + data
open(os.path.join(d, "esp-mic-merged.bin"), "wb").write(out)
print(len(out), "bytes ->", os.path.join(d, "esp-mic-merged.bin"))
