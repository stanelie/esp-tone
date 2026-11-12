#!/usr/bin/env python3
"""Find an ESP8266 tone server on the LAN and open its stream in VLC.

Discovery: mDNS / DNS-SD service _esptone._tcp (needs `pip install zeroconf`)
and a scan of the attached network (real netmask, capped at a /20; or
--subnet) for TCP port 1234 run at the same time; the first one to find a
device wins, and the script says which. The scan works when multicast is
blocked. Nothing is remembered between runs. Use --diagnose to see how each
method does on its own.

Usage:
  python tone_player.py                 # discover, pick if several, open VLC
  python tone_player.py --host 10.8.1.168   # skip discovery
  python tone_player.py --print-only    # just print the stream URL
"""
import argparse
import concurrent.futures
import ipaddress
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request

SERVICE_TYPE = "_esptone._tcp.local."
DEFAULT_PORT = 1234


def fetch_info(host, port, timeout=1.0):
    """Ask a candidate for /info. Returns the parsed JSON or None."""
    try:
        with urllib.request.urlopen(f"http://{host}:{port}/info", timeout=timeout) as r:
            # bounded read: a device on older firmware streams audio forever
            return json.loads(r.read(1024).decode("utf-8", "replace").strip().splitlines()[-1])
    except Exception:
        return None


def discover_mdns(timeout):
    try:
        from zeroconf import ServiceBrowser, ServiceListener, Zeroconf
    except ImportError:
        print("zeroconf not installed (pip install zeroconf); skipping mDNS", file=sys.stderr)
        return []

    found = {}
    lock = threading.Lock()

    class Listener(ServiceListener):
        def add_service(self, zc, type_, name):
            info = zc.get_service_info(type_, name, 2000)
            if info and info.parsed_addresses():
                with lock:
                    found[name] = (info.parsed_addresses()[0], info.port, name.split(".")[0])

        def update_service(self, zc, type_, name):
            self.add_service(zc, type_, name)

        def remove_service(self, zc, type_, name):
            with lock:
                found.pop(name, None)

    zc = Zeroconf()
    try:
        ServiceBrowser(zc, SERVICE_TYPE, Listener())
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            time.sleep(0.05)
            with lock:
                if found:
                    break
        if found:
            time.sleep(0.3)   # brief grace period so a second device can answer too
    finally:
        zc.close()
    with lock:
        return list(found.values())


def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))   # no packet is sent
        return s.getsockname()[0]
    finally:
        s.close()


def local_network():
    """The attached IPv4 network, using the real prefix length from the OS."""
    ip = local_ip()
    prefix = None
    try:
        if sys.platform == "win32":
            out = subprocess.run(
                ["powershell", "-NoProfile", "-Command",
                 f"(Get-NetIPAddress -AddressFamily IPv4 -IPAddress {ip}).PrefixLength"],
                capture_output=True, text=True, timeout=15).stdout
            prefix = int(out.split()[0])
        elif sys.platform == "darwin":
            out = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=10).stdout
            m = re.search(rf"inet {re.escape(ip)} netmask (0x[0-9a-fA-F]+)", out)
            prefix = bin(int(m.group(1), 16)).count("1")
        else:
            out = subprocess.run(["ip", "-o", "-4", "addr", "show"],
                                 capture_output=True, text=True, timeout=10).stdout
            prefix = int(re.search(rf"{re.escape(ip)}/(\d+)", out).group(1))
    except (OSError, ValueError, IndexError, AttributeError, subprocess.SubprocessError):
        pass
    if prefix is None:
        print("Could not read the netmask; assuming /24 (use --subnet)", file=sys.stderr)
        prefix = 24
    return ipaddress.ip_network(f"{ip}/{prefix}", strict=False)


MAX_SCAN_PREFIX = 20   # never scan more than a /20 (4094 hosts)


def discover_scan(port, subnet=None, timeout=0.4):
    if subnet:
        net = ipaddress.ip_network(subnet, strict=False)
    else:
        net = local_network()
        if net.prefixlen < MAX_SCAN_PREFIX:
            print(f"{net} is large; scanning only the /{MAX_SCAN_PREFIX} around {local_ip()}",
                  file=sys.stderr)
            net = ipaddress.ip_network(f"{local_ip()}/{MAX_SCAN_PREFIX}", strict=False)
    print(f"Scanning {net} on port {port}...", file=sys.stderr)

    def probe(host):
        host = str(host)
        try:
            with socket.create_connection((host, port), timeout=timeout):
                pass
        except OSError:
            return None
        info = fetch_info(host, port)
        if info and "rate" in info:
            return (host, port, info.get("name", host))
        return None

    with concurrent.futures.ThreadPoolExecutor(max_workers=128) as pool:
        return [r for r in pool.map(probe, net.hosts()) if r]


def find_vlc(explicit=None):
    if explicit:
        return explicit
    candidates = [
        shutil.which("vlc"),
        r"C:\Program Files\VideoLAN\VLC\vlc.exe",
        r"C:\Program Files (x86)\VideoLAN\VLC\vlc.exe",
        "/Applications/VLC.app/Contents/MacOS/VLC",
    ]
    for c in candidates:
        if c and os.path.exists(c):
            return c
    return None


def discover_parallel(args, wait_for_all=False):
    """Run mDNS and the subnet scan together.

    Normally the first method to find something wins. With wait_for_all (used
    by --diagnose) every method runs to completion and its result and timing
    are reported, so you can see which one is failing.
    """
    import queue
    results = queue.Queue()
    methods = [("mDNS", lambda: discover_mdns(args.timeout))]
    if not args.no_scan:
        methods.append(("scan", lambda: discover_scan(args.port, args.subnet)))
    start = time.monotonic()

    def run(label, fn):
        try:
            found = fn()
        except Exception as e:   # one method failing must not stop the other
            print(f"{label} failed: {e}", file=sys.stderr)
            found = []
        results.put((label, found, time.monotonic() - start))

    for label, fn in methods:
        threading.Thread(target=run, args=(label, fn), daemon=True).start()

    winner = []
    for _ in methods:
        label, found, took = results.get()
        status = ", ".join(f"{h}:{p}" for h, p, _ in found) if found else "nothing"
        if wait_for_all:
            print(f"  {label:5s} {took:5.1f}s  {status}", file=sys.stderr)
        elif found:
            print(f"Found via {label} in {took:.1f}s", file=sys.stderr)
            return found
        if found and not winner:
            winner = found
    return winner


def choose(devices):
    if len(devices) == 1:
        return devices[0]
    print("Several devices found:")
    for i, (host, port, name) in enumerate(devices, 1):
        print(f"  {i}. {name}  {host}:{port}")
    while True:
        try:
            n = int(input("Pick one: "))
            return devices[n - 1]
        except (ValueError, IndexError):
            print("Invalid choice")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", help="skip discovery and use this host/IP")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--timeout", type=float, default=3.0, help="mDNS browse time in seconds")
    ap.add_argument("--no-scan", action="store_true", help="mDNS only, do not scan the subnet")
    ap.add_argument("--diagnose", action="store_true",
                    help="run mDNS and the scan to completion and report each method's result and timing")
    ap.add_argument("--subnet", help="CIDR range for the scan, e.g. 10.8.0.0/22 (default: the attached network)")
    ap.add_argument("--vlc", help="path to the VLC executable")
    ap.add_argument("--print-only", action="store_true", help="print the stream URL and exit")
    args = ap.parse_args()

    if args.host:
        devices = [(args.host, args.port, args.host)]
    else:
        devices = discover_parallel(args, wait_for_all=args.diagnose)
        if args.diagnose:
            return 0 if devices else 1
        if not devices:
            print("No tone server found. Try --host <ip>.", file=sys.stderr)
            return 1

    host, port, name = choose(devices)
    url = f"http://{host}:{port}"        # no trailing slash
    print(f"{name}: {url}")
    if args.print_only:
        return 0

    vlc = find_vlc(args.vlc)
    if not vlc:
        print("VLC not found; open the URL above manually or pass --vlc", file=sys.stderr)
        return 1
    subprocess.Popen([vlc, url])
    return 0


if __name__ == "__main__":
    sys.exit(main())
