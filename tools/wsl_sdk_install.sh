#!/bin/bash
# Installs ESP8266_RTOS_SDK v3.4 and its toolchain inside WSL (Linux checkout avoids CRLF issues).
set -e
# The SDK's scripts call "python"; Ubuntu 24.04 only has python3.
mkdir -p ~/.local/bin
ln -sf /usr/bin/python3 ~/.local/bin/python
export PATH="$HOME/.local/bin:$PATH"
mkdir -p ~/esp
cd ~/esp
if [ ! -d ESP8266_RTOS_SDK ]; then
  git clone -b release/v3.4 --recursive https://github.com/espressif/ESP8266_RTOS_SDK.git
fi
cd ESP8266_RTOS_SDK
# install.sh wants "pip install --user virtualenv", which Ubuntu 24.04 refuses (PEP 668). It reuses
# an existing env at this path, so create it with the stock venv module instead.
ENV=~/.espressif/python_env/rtos3.4_py3.12_env
[ -x "$ENV/bin/python" ] || python3 -m venv "$ENV"
./install.sh
echo "=== INSTALL DONE ==="
