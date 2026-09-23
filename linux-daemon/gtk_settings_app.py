#!/usr/bin/env python3
"""
Zohara Link Linux Settings & Status Client
A reference Python GTK/Qt/CLI client demonstrating how Arch Linux desktop apps,
settings panels, and status bars interface with the local Unix domain socket IPC.
"""

import asyncio
import json
import os
import sys

UNIX_SOCKET_PATH = f"/run/user/{os.getuid()}/zohara.sock" if hasattr(os, "getuid") else "/tmp/zohara.sock"

async def main():
    if not os.path.exists(UNIX_SOCKET_PATH):
        print(f"[Error] Zohara daemon socket not found at {UNIX_SOCKET_PATH}. Is zohara-linkd running?")
        sys.exit(1)

    reader, writer = await asyncio.open_unix_connection(UNIX_SOCKET_PATH)
    print("=== Connected to Zohara Link Linux Daemon ===")

    # Request system status
    req = json.dumps({"command": "GET_STATUS"}) + "\n"
    writer.write(req.encode("utf-8"))
    await writer.drain()

    response = await reader.readline()
    data = json.loads(response.decode("utf-8"))
    print("\n--- Current Ecosystem Status ---")
    print(f"Paired Devices: {len(data.get('paired_devices', []))}")
    for dev in data.get("paired_devices", []):
        print(f"  - {dev.get('device_name')} (ID: {dev.get('device_id')}, Last IP: {dev.get('last_ip')})")
    
    print("\n--- Live Device Telemetry ---")
    for dev_id, tel in data.get("telemetry", {}).items():
        battery = tel.get("batteryLevel", "N/A")
        charging = "⚡ Charging" if tel.get("isCharging") else "🔋 On Battery"
        wifi = tel.get("wifiSsid", "Unknown")
        print(f"  [{dev_id}] Battery: {battery}% ({charging}) | Wi-Fi: {wifi}")

    # Listen for asynchronous daemon events
    print("\nListening for real-time daemon events (Ctrl+C to quit)...")
    try:
        while True:
            line = await reader.readline()
            if not line:
                break
            event = json.loads(line.decode("utf-8"))
            print(f">> Event [{event.get('event')}]: {event.get('data')}")
    except KeyboardInterrupt:
        pass
    finally:
        writer.close()
        await writer.wait_closed()

if __name__ == "__main__":
    asyncio.run(main())
