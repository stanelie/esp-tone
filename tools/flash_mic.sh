#!/bin/bash
# Flashes the microphone firmware. Hold BOOT (GPIO0 low) while plugging the programmer in, then run:
#   tools/flash_mic.sh                 update: app only at 0x10000, keeps Wi-Fi and settings
#   tools/flash_mic.sh --full          blank chip: everything at 0x0, ERASES settings (set Wi-Fi again with AT+WIFI)
# PORT defaults to the first /dev/cu.usbserial-* (the CH340 programmer); override with PORT=/dev/... .
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../ESP8266-WiFi-MICROPHONE/build-esp01"
PORT="${PORT:-$(ls /dev/cu.usbserial-* 2>/dev/null | head -1)}"
[ -n "$PORT" ] || { echo "No /dev/cu.usbserial-* found; set PORT=..."; exit 1; }
if [ "$1" = "--full" ]; then ADDR=0x0; IMG="$OUT/esp-mic-merged.bin"; else ADDR=0x10000; IMG="$OUT/esp-mic-app.bin"; fi
echo "Flashing $IMG at $ADDR via $PORT"
python3 -m esptool --chip esp8266 --port "$PORT" --baud 115200 --before no_reset \
    write_flash --flash_mode dout --flash_size 1MB "$ADDR" "$IMG"
