#!/usr/bin/env python3
"""
Zohara Link Daemon (zohara-linkd)
A native, lightweight Arch Linux system daemon providing encrypted companion synchronization
with Android devices (TLS Sockets, mDNS/Avahi, D-Bus IPC, Wayland/X11 clipboard, MPRIS2, and notifications).

Usage:
    python3 daemon.py [--port 42424] [--no-dbus]
"""

import asyncio
import json
import os
import ssl
import subprocess
import sys
import time
import socket
import logging
from dataclasses import dataclass, asdict
from typing import Dict, Optional, Set

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] [zohara-linkd] %(message)s'
)
logger = logging.getLogger("zohara-linkd")

CONFIG_DIR = os.path.expanduser("~/.config/zohara-link")
DOWNLOADS_DIR = os.path.expanduser("~/Downloads/ZoharaLink")
PAIRED_DEVICES_FILE = os.path.join(CONFIG_DIR, "paired_devices.json")
CERT_FILE = os.path.join(CONFIG_DIR, "daemon.crt")
KEY_FILE = os.path.join(CONFIG_DIR, "daemon.key")
UNIX_SOCKET_PATH = f"/run/user/{os.getuid()}/zohara.sock" if hasattr(os, "getuid") else "/tmp/zohara.sock"
DEFAULT_PORT = 42424
MDNS_SERVICE_TYPE = "_zohara-link._tcp.local."
MDNS_SERVICE_NAME = f"ZoharaLinux-{socket.gethostname()}._zohara-link._tcp.local."

os.makedirs(CONFIG_DIR, exist_ok=True)
os.makedirs(DOWNLOADS_DIR, exist_ok=True)

def generate_self_signed_cert():
    """Generates TLS self-signed certificates using openssl if not present."""
    if not (os.path.exists(CERT_FILE) and os.path.exists(KEY_FILE)):
        logger.info("Generating self-signed TLS certificates for Zohara Link daemon...")
        cmd = [
            "openssl", "req", "-x509", "-newkey", "rsa:2048",
            "-keyout", KEY_FILE, "-out", CERT_FILE,
            "-days", "3650", "-nodes",
            "-subj", f"/CN={socket.gethostname()}/O=ZoharaLink/OU=LinuxEcosystem"
        ]
        try:
            subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            logger.info("Certificates successfully created at %s", CONFIG_DIR)
        except Exception as e:
            logger.warning("OpenSSL certificate generation fallback: %s", e)

@dataclass
class PairedDevice:
    device_id: str
    device_name: str
    paired_at: float
    client_cert_thumbprint: str
    last_ip: str

class ZoharaDaemon:
    def __init__(self, port: int = DEFAULT_PORT):
        self.port = port
        self.paired_devices: Dict[str, PairedDevice] = self._load_paired_devices()
        self.active_connections: Dict[str, asyncio.StreamWriter] = {}
        self.device_telemetry: Dict[str, dict] = {}
        self.pending_pairings: Dict[str, str] = {}  # device_id -> 6-digit PIN
        self.active_file_transfers: Dict[str, dict] = {}
        self.last_clipboard_text: str = ""
        self.unix_clients: Set[asyncio.StreamWriter] = set()
        # Connection-scoped ids (handle_client's "dev_<ip>_<port>") that have
        # completed PAIR_VERIFY *on this TCP connection*. Nothing else in
        # this class ever checked self.paired_devices before acting on a
        # packet, so any socket that could reach port 4545 -- paired or
        # not -- could already move the mouse, click, read/write the
        # clipboard, or write files under Downloads/ZoharaLink. See
        # handle_packet()'s gate below.
        #
        # This is connection-scoped, not device-scoped: there is no
        # persistent session token or verified client certificate yet
        # (client_cert_thumbprint is a stub -- always "trusted" when not
        # supplied, and the TLS context never sets verify_mode =
        # ssl.CERT_REQUIRED), so a previously-paired device currently has
        # to redo PAIR_REQUEST/PAIR_VERIFY on every reconnect. That's a
        # real UX cost, not a bug in this fix -- there is no cryptographic
        # proof of "same device as before" available to check instead.
        self.authenticated_connections: Set[str] = set()

    def _load_paired_devices(self) -> Dict[str, PairedDevice]:
        if os.path.exists(PAIRED_DEVICES_FILE):
            try:
                with open(PAIRED_DEVICES_FILE, "r") as f:
                    data = json.load(f)
                    return {k: PairedDevice(**v) for k, v in data.items()}
            except Exception as e:
                logger.error("Error loading paired devices: %s", e)
        return {}

    def _save_paired_devices(self):
        try:
            with open(PAIRED_DEVICES_FILE, "w") as f:
                data = {k: asdict(v) for k, v in self.paired_devices.items()}
                json.dump(data, f, indent=2)
        except Exception as e:
            logger.error("Error saving paired devices: %s", e)

    async def broadcast_local_ipc(self, event_type: str, data: dict):
        """Broadcasts event to local Unix domain socket clients (GTK/Waybar/custom UI)."""
        msg = json.dumps({"event": event_type, "data": data}) + "\n"
        dead_clients = set()
        for client in self.unix_clients:
            try:
                client.write(msg.encode("utf-8"))
                await client.drain()
            except Exception:
                dead_clients.add(client)
        self.unix_clients -= dead_clients

    # ----------------- Protocol Packet Handlers -----------------

    async def handle_packet(self, device_id: str, packet: dict, writer: asyncio.StreamWriter):
        ptype = packet.get("type")
        logger.info(f"Received packet [{ptype}] from {device_id}")

        # Everything except the pairing handshake itself requires this
        # connection to have completed PAIR_VERIFY. Before this check
        # existed, ANY socket that could reach this port -- paired or
        # not -- could already move the mouse, click, sync the
        # clipboard, mirror notifications, or write files under
        # Downloads/ZoharaLink via FILE_OFFER/FILE_CHUNK.
        if ptype not in ("PAIR_REQUEST", "PAIR_VERIFY") and device_id not in self.authenticated_connections:
            logger.warning(f"Rejected [{ptype}] from unauthenticated connection {device_id}")
            await self.send_json(writer, {"type": "ERROR", "error": "not paired on this connection"})
            return

        if ptype == "PAIR_REQUEST":
            # 6-digit SAS Pin generation
            req_device_id = packet.get("deviceId", device_id)
            req_device_name = packet.get("deviceName", "Android Device")
            pin = f"{int.from_bytes(os.urandom(3), 'big') % 900000 + 100000}"
            self.pending_pairings[req_device_id] = pin
            logger.info(f"*** PAIRING AUTHORIZATION REQUIRED ***")
            logger.info(f"Device: {req_device_name} ({req_device_id})")
            logger.info(f"Verification PIN (SAS): >>> {pin} <<<")

            # Emit system notification on Linux desktop for pairing authorization
            self.send_desktop_notification(
                "Zohara Link - Pairing Request",
                f"Device '{req_device_name}' wants to pair.\nSAS Verification PIN: {pin}"
            )
            await self.broadcast_local_ipc("PAIR_REQUEST", {
                "deviceId": req_device_id,
                "deviceName": req_device_name,
                "pin": pin
            })

            # NOTE: the PIN is NOT included here. This is a Short
            # Authentication String pairing flow: its whole security
            # property depends on the PIN reaching the human over a
            # channel the connecting device doesn't control -- the
            # desktop notification above -- so the user reads it there
            # and enters it on the phone. Echoing it back to the same
            # socket that just asked to pair would let any device on
            # the LAN complete PAIR_VERIFY by simply replaying the value
            # it was just handed; this used to do exactly that.
            challenge = {
                "type": "PAIR_CHALLENGE",
                "serverName": socket.gethostname(),
                "timestamp": int(time.time())
            }
            await self.send_json(writer, challenge)

        elif ptype == "PAIR_VERIFY":
            req_device_id = packet.get("deviceId")
            user_pin = packet.get("pinSas")
            req_device_name = packet.get("deviceName", "Android Device")
            expected_pin = self.pending_pairings.get(req_device_id)

            # `or packet.get("approved") is True` used to sit here, which
            # meant any client could skip the PIN entirely by setting
            # that one field -- since it's the client's own claim about
            # itself, not something the desktop user approved.
            if expected_pin and user_pin == expected_pin:
                paired_dev = PairedDevice(
                    device_id=req_device_id,
                    device_name=req_device_name,
                    paired_at=time.time(),
                    client_cert_thumbprint=packet.get("clientThumbprint", "trusted"),
                    last_ip=writer.get_extra_info("peername")[0]
                )
                self.paired_devices[req_device_id] = paired_dev
                self._save_paired_devices()
                self.pending_pairings.pop(req_device_id, None)
                self.authenticated_connections.add(device_id)

                logger.info(f"Device '{req_device_name}' paired successfully!")
                await self.send_json(writer, {
                    "type": "PAIR_CONFIRMED",
                    "status": "SUCCESS",
                    "serverName": socket.gethostname()
                })
                await self.broadcast_local_ipc("DEVICE_PAIRED", asdict(paired_dev))
            else:
                await self.send_json(writer, {
                    "type": "PAIR_CONFIRMED",
                    "status": "FAILED",
                    "error": "PIN mismatch"
                })

        elif ptype == "CLIPBOARD_SYNC":
            text = packet.get("text", "")
            if text and text != self.last_clipboard_text:
                self.last_clipboard_text = text
                self.set_linux_clipboard(text)
                await self.broadcast_local_ipc("CLIPBOARD_UPDATED", {"text": text, "source": "android"})

        elif ptype == "TELEMETRY_STATUS":
            self.device_telemetry[device_id] = packet
            await self.broadcast_local_ipc("TELEMETRY_UPDATED", packet)

        elif ptype == "NOTIFICATION_POST":
            title = packet.get("title", "Android Notification")
            text = packet.get("text", "")
            app_name = packet.get("appName", "Phone")
            notif_id = packet.get("notifId", "")
            self.send_desktop_notification(f"[{app_name}] {title}", text)
            await self.broadcast_local_ipc("NOTIFICATION_POSTED", packet)

        elif ptype == "FILE_OFFER":
            transfer_id = packet.get("transferId")
            file_name = packet.get("fileName", "received_file")
            file_size = packet.get("fileSize", 0)
            dest_path = os.path.join(DOWNLOADS_DIR, os.path.basename(file_name))

            self.active_file_transfers[transfer_id] = {
                "file_name": file_name,
                "file_size": file_size,
                "dest_path": dest_path,
                "handle": open(dest_path, "wb"),
                "received_bytes": 0,
                "start_time": time.time()
            }
            logger.info(f"Accepting incoming file: {file_name} ({file_size} bytes)")
            await self.send_json(writer, {
                "type": "FILE_ACCEPT",
                "transferId": transfer_id,
                "accepted": True
            })
            await self.broadcast_local_ipc("FILE_TRANSFER_START", {"transferId": transfer_id, "fileName": file_name, "fileSize": file_size})

        elif ptype == "FILE_CHUNK":
            transfer_id = packet.get("transferId")
            if transfer_id in self.active_file_transfers:
                info = self.active_file_transfers[transfer_id]
                chunk_b64 = packet.get("payloadBase64", "")
                import base64
                data = base64.b64decode(chunk_b64)
                info["handle"].write(data)
                info["received_bytes"] += len(data)

                # Progress broadcast
                progress = min(100.0, (info["received_bytes"] / max(1, info["file_size"])) * 100.0)
                await self.broadcast_local_ipc("FILE_TRANSFER_PROGRESS", {
                    "transferId": transfer_id,
                    "progress": progress,
                    "received": info["received_bytes"],
                    "total": info["file_size"]
                })

        elif ptype == "FILE_COMPLETE":
            transfer_id = packet.get("transferId")
            if transfer_id in self.active_file_transfers:
                info = self.active_file_transfers.pop(transfer_id)
                info["handle"].close()
                logger.info(f"File transfer complete: {info['dest_path']}")
                self.send_desktop_notification("File Received", f"Saved {info['file_name']} to Downloads/ZoharaLink")
                await self.broadcast_local_ipc("FILE_TRANSFER_COMPLETE", {"transferId": transfer_id, "filePath": info["dest_path"]})

        elif ptype == "INPUT_EVENT":
            action = packet.get("action")
            if action == "MOVE":
                dx = packet.get("dx", 0)
                dy = packet.get("dy", 0)
                # Wayland ydotool / xdotool emulation
                self.simulate_pointer_move(dx, dy)
            elif action == "CLICK":
                button = packet.get("button", "LEFT")
                self.simulate_pointer_click(button)

        elif ptype == "MEDIA_CONTROL":
            action = packet.get("action")
            self.control_mpris2_media(action)

        elif ptype == "PROXIMITY_HEARTBEAT":
            # Proximity heartbeat updated
            pass

    async def send_json(self, writer: asyncio.StreamWriter, obj: dict):
        line = json.dumps(obj) + "\n"
        writer.write(line.encode("utf-8"))
        await writer.drain()

    # ----------------- Linux Desktop Integrations -----------------

    def send_desktop_notification(self, title: str, body: str):
        try:
            subprocess.run(["notify-send", "-a", "Zohara Link", title, body], check=False)
        except Exception as e:
            logger.debug(f"notify-send fallback: {e}")

    def set_linux_clipboard(self, text: str):
        """Sets clipboard in Wayland (wl-copy) or X11 (xclip)."""
        try:
            if os.environ.get("WAYLAND_DISPLAY"):
                p = subprocess.Popen(["wl-copy"], stdin=subprocess.PIPE)
                p.communicate(input=text.encode("utf-8"))
            else:
                p = subprocess.Popen(["xclip", "-selection", "clipboard"], stdin=subprocess.PIPE)
                p.communicate(input=text.encode("utf-8"))
        except Exception as e:
            logger.debug(f"Clipboard write failed: {e}")

    def get_linux_clipboard(self) -> str:
        try:
            if os.environ.get("WAYLAND_DISPLAY"):
                return subprocess.check_output(["wl-paste", "-n"]).decode("utf-8")
            else:
                return subprocess.check_output(["xclip", "-selection", "clipboard", "-o"]).decode("utf-8")
        except Exception:
            return ""

    def simulate_pointer_move(self, dx: float, dy: float):
        try:
            subprocess.run(["ydotool", "mousemove", "--", str(int(dx)), str(int(dy))], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            try:
                subprocess.run(["xdotool", "mousemove_relative", "--", str(int(dx)), str(int(dy))], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except Exception:
                pass

    def simulate_pointer_click(self, button: str):
        btn_num = "1" if button == "LEFT" else "3"
        try:
            subprocess.run(["ydotool", "click", "0x110" if button == "LEFT" else "0x111"], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            try:
                subprocess.run(["xdotool", "click", btn_num], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except Exception:
                pass

    def control_mpris2_media(self, action: str):
        try:
            cmd = "play-pause" if action == "PLAY_PAUSE" else ("next" if action == "NEXT" else "previous")
            subprocess.run(["playerctl", cmd], check=False)
        except Exception as e:
            logger.debug(f"playerctl command failed: {e}")

    def trigger_screen_lock(self):
        """Locks Linux desktop session."""
        logger.info("Triggering Proximity Screen Lock...")
        for lock_cmd in [["hyprlock"], ["swaylock"], ["loginctl", "lock-session"]]:
            try:
                subprocess.run(lock_cmd, check=True)
                break
            except Exception:
                continue

    # ----------------- Async Server Loops -----------------

    async def handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        peer = writer.get_extra_info("peername")
        logger.info(f"Incoming connection from {peer}")
        device_id = f"dev_{peer[0]}_{peer[1]}"

        try:
            while True:
                line = await reader.readline()
                if not line:
                    break
                data = line.decode("utf-8").strip()
                if not data:
                    continue
                try:
                    packet = json.loads(data)
                    await self.handle_packet(device_id, packet, writer)
                except json.JSONDecodeError:
                    logger.warning(f"Invalid JSON received: {data}")
        except Exception as e:
            logger.warning(f"Connection error with {peer}: {e}")
        finally:
            logger.info(f"Connection closed for {peer}")
            writer.close()
            await writer.wait_closed()
            self.active_connections.pop(device_id, None)
            self.authenticated_connections.discard(device_id)

    async def handle_unix_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        """Local IPC for GTK/Qt settings apps or Waybar status scripts."""
        self.unix_clients.add(writer)
        logger.info("Local IPC client connected")
        try:
            while True:
                line = await reader.readline()
                if not line:
                    break
                cmd_data = line.decode("utf-8").strip()
                if not cmd_data:
                    continue
                req = json.loads(cmd_data)
                cmd = req.get("command")
                resp = {}
                if cmd == "GET_STATUS":
                    resp = {
                        "paired_devices": [asdict(d) for d in self.paired_devices.values()],
                        "telemetry": self.device_telemetry,
                        "active_transfers": list(self.active_file_transfers.keys())
                    }
                elif cmd == "SEND_CLIPBOARD":
                    text = req.get("text", "")
                    for dev_writer in self.active_connections.values():
                        await self.send_json(dev_writer, {"type": "CLIPBOARD_SYNC", "text": text})
                    resp = {"status": "OK"}
                elif cmd == "LOCK_SCREEN":
                    self.trigger_screen_lock()
                    resp = {"status": "OK"}
                await self.send_json(writer, resp)
        except Exception:
            pass
        finally:
            self.unix_clients.discard(writer)

    async def clipboard_watcher_loop(self):
        """Watches Linux desktop clipboard and pushes updates to Android."""
        while True:
            await asyncio.sleep(1.0)
            current = self.get_linux_clipboard()
            if current and current != self.last_clipboard_text:
                self.last_clipboard_text = current
                logger.info("Desktop clipboard changed, pushing to paired Android devices...")
                packet = {"type": "CLIPBOARD_SYNC", "text": current, "source": "linux", "timestamp": int(time.time())}
                for dev_writer in list(self.active_connections.values()):
                    try:
                        await self.send_json(dev_writer, packet)
                    except Exception:
                        pass

    async def run(self):
        generate_self_signed_cert()

        # Setup TLS context
        ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        try:
            ssl_ctx.load_cert_chain(certfile=CERT_FILE, keyfile=KEY_FILE)
        except Exception as e:
            logger.warning(f"Starting in standard TCP mode (TLS cert note: {e})")
            ssl_ctx = None

        # Start TCP/TLS server for Android devices
        server = await asyncio.start_server(
            self.handle_client,
            "0.0.0.0",
            self.port,
            ssl=ssl_ctx
        )
        logger.info(f"Zohara Link TCP daemon listening on 0.0.0.0:{self.port} (TLS: {ssl_ctx is not None})")

        # Start Local Unix Socket IPC Server
        if os.path.exists(UNIX_SOCKET_PATH):
            try:
                os.unlink(UNIX_SOCKET_PATH)
            except OSError:
                pass
        unix_server = await asyncio.start_unix_server(self.handle_unix_client, path=UNIX_SOCKET_PATH)
        logger.info(f"Local IPC Unix Domain Socket active at {UNIX_SOCKET_PATH}")

        # Start clipboard watcher
        asyncio.create_task(self.clipboard_watcher_loop())

        async with server, unix_server:
            await asyncio.gather(server.serve_forever(), unix_server.serve_forever())

if __name__ == "__main__":
    daemon = ZoharaDaemon()
    try:
        asyncio.run(daemon.run())
    except KeyboardInterrupt:
        logger.info("Daemon stopped by user.")
