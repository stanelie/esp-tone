#!/bin/bash
# Builds the microphone firmware (ESP8266-WiFi-MICROPHONE with our local changes) in place, on macOS
# or Linux, and puts the images in ESP8266-WiFi-MICROPHONE/build-esp01/:
#   esp-mic-app.bin     the app alone: flash at 0x10000 to update (keeps Wi-Fi and settings)
#   esp-mic-merged.bin  everything: flash at 0x0 on a blank chip (erases settings)
# Needs ESP8266_RTOS_SDK v3.4 at $IDF_PATH (default ~/esp/ESP8266_RTOS_SDK); see NOTES.md.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$HERE/../ESP8266-WiFi-MICROPHONE"
export IDF_PATH="${IDF_PATH:-$HOME/esp/ESP8266_RTOS_SDK}"
export IDF_TOOLS_PATH="${IDF_TOOLS_PATH:-$HOME/.espressif}"
# Build the environment directly rather than sourcing export.sh: on macOS export.sh
# resolves bare `python` to the /usr/bin/python xcode stub and never exports IDF_PATH.
# Put the SDK's own venv (its `python` + cmake/ninja) and the xtensa toolchain first on PATH.
VENVBIN="$(ls -d "$IDF_TOOLS_PATH"/python_env/*/bin 2>/dev/null | head -1)"
GCCBIN="$(dirname "$(ls "$IDF_TOOLS_PATH"/tools/xtensa-lx106-elf/*/xtensa-lx106-elf/bin/xtensa-lx106-elf-gcc 2>/dev/null | head -1)")"
[ -n "$VENVBIN" ] || { echo "No SDK python_env under $IDF_TOOLS_PATH; run install.sh (see NOTES.md)"; exit 1; }
export PATH="$VENVBIN:$GCCBIN:$PATH"
PY="$VENVBIN/python"; [ -x "$PY" ] || PY=python3
cd "$PROJ/firmware"
"$PY" "$IDF_PATH/tools/idf.py" build
OUT="$PROJ/build-esp01"
mkdir -p "$OUT"
cp build/bootloader/bootloader.bin build/partition_table/partition-table.bin build/phy_init_data.bin \
   build/esp-adpcm-streamer.bin "$OUT/"
cp build/esp-adpcm-streamer.bin "$OUT/esp-mic-app.bin"
python3 "$HERE/merge_mic.py"
ls -l "$OUT"/esp-mic-*.bin
