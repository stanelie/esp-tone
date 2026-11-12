#!/bin/bash
# Builds the v2 throughput spike (firmware-v2/) and copies the images next to it.
# Same environment handling as build_mic.sh: do NOT source export.sh (on macOS it resolves bare
# `python` to the Xcode stub and never exports IDF_PATH) - drive idf.py with the SDK venv directly.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$HERE/../firmware-v2"
export IDF_PATH="${IDF_PATH:-$HOME/esp/ESP8266_RTOS_SDK}"
export IDF_TOOLS_PATH="${IDF_TOOLS_PATH:-$HOME/.espressif}"
VENVBIN="$(ls -d "$IDF_TOOLS_PATH"/python_env/*/bin 2>/dev/null | head -1)"
GCCBIN="$(dirname "$(ls "$IDF_TOOLS_PATH"/tools/xtensa-lx106-elf/*/xtensa-lx106-elf/bin/xtensa-lx106-elf-gcc 2>/dev/null | head -1)")"
[ -n "$VENVBIN" ] || { echo "No SDK python_env under $IDF_TOOLS_PATH; see SPEC.md 4.8"; exit 1; }
export PATH="$VENVBIN:$GCCBIN:$PATH"
PY="$VENVBIN/python"; [ -x "$PY" ] || PY=python3
[ -f "$PROJ/main/wifi_config.h" ] || { echo "Create $PROJ/main/wifi_config.h (see wifi_config.h.example)"; exit 1; }
cd "$PROJ"
"$PY" "$IDF_PATH/tools/idf.py" build
ls -l "$PROJ"/build/*.bin
