#!/bin/bash
# Builds ESP8266-WiFi-MICROPHONE with ESP8266_RTOS_SDK v3.4 inside WSL.
# A clean PATH: the inherited Windows PATH has spaces and parentheses that confuse the SDK scripts.
export PATH="$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin"
# The SDK's dependency check imports pkg_resources, which setuptools 81+ no longer ships.
~/.espressif/python_env/rtos3.4_py3.12_env/bin/pip install -q "setuptools<81"
cd ~/esp
[ -d ESP8266-WiFi-MICROPHONE ] || git clone https://github.com/Dzantemir/ESP8266-WiFi-MICROPHONE.git
cd ESP8266-WiFi-MICROPHONE/firmware
# Local change: Wi-Fi power save off. The shipped config uses MAX_MODEM, and modem sleep starves the
# radio at streaming rates (sendto fails with ENOMEM and the stream stops).
sed -i -e 's/^CONFIG_STREAMER_WIFI_PS_MAX_MODEM=y/# CONFIG_STREAMER_WIFI_PS_MAX_MODEM is not set/' \
       -e 's/^# CONFIG_STREAMER_WIFI_PS_NONE is not set/CONFIG_STREAMER_WIFI_PS_NONE=y/' \
       -e 's/^CONFIG_STREAMER_WIFI_PS_MODE=.*/CONFIG_STREAMER_WIFI_PS_MODE=0/' \
       -e "s/^CONFIG_ESP8266_WIFI_TX_PKT_NUM=.*/CONFIG_ESP8266_WIFI_TX_PKT_NUM=${1:-10}/" sdkconfig
grep -E "STREAMER_WIFI_PS|WIFI_TX_PKT_NUM" sdkconfig
# Local changes are made in the Windows clone (UDP send retry, strongest-AP scan, AT over the
# network); bring its sources over.
cp -r /mnt/c/Users/selie/Documents/esp-tone/ESP8266-WiFi-MICROPHONE/firmware/main/. main/
. ~/esp/ESP8266_RTOS_SDK/export.sh > /tmp/export.log 2>&1   # sourced directly (a pipe would run it in a subshell)
idf.py build > /tmp/build.log 2>&1
status=$?
tail -5 /tmp/build.log
grep -n "WIFI_PS_MODE" build/config/sdkconfig.h
OUT=/mnt/c/Users/selie/Documents/esp-tone/ESP8266-WiFi-MICROPHONE/build-esp01
cd build && cp bootloader/bootloader.bin partition_table/partition-table.bin phy_init_data.bin esp-adpcm-streamer.bin $OUT/
exit $status
