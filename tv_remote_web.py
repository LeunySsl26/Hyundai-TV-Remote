#!/usr/bin/env python3
"""
Hyundai TV Remote - Web Server for Wi-Fi Browser Control
Allows controlling your Hyundai webOS TV (HYLED4026WiM) from any web browser on your Wi-Fi network.
"""

import asyncio
import json
import os
import re
import socket
import ssl
import sys
import websockets

TV_IP_DEFAULT = "192.168.1.3"
TV_PORT = 3001
CLIENT_KEY_DEFAULT = "a57b50c45610a3b701c9f3a195d32696"
HTTP_PORT = 8080

HTML_FILE_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "HyundaiRemoteApp", "res", "raw", "remote_web.html"
)
DIRECT_HTML_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "HyundaiRemoteApp", "res", "raw", "remote_direct.html"
)

# Global TV Controller State
class WebOsClient:
    def __init__(self, tv_ip=TV_IP_DEFAULT, client_key=CLIENT_KEY_DEFAULT):
        self.tv_ip = tv_ip
        self.client_key = client_key
        self.main_ws = None
        self.pointer_ws = None
        self.connected = False
        self.volume = 36
        self.muted = False
        self.req_id = 1
        self.ssl_ctx = ssl.create_default_context()
        self.ssl_ctx.check_hostname = False
        self.ssl_ctx.verify_mode = ssl.CERT_NONE

    async def connect(self):
        while True:
            try:
                uri = f"wss://{self.tv_ip}:{TV_PORT}"
                async with websockets.connect(uri, ssl=self.ssl_ctx) as ws:
                    self.main_ws = ws
                    self.connected = True
                    print(f"[*] Conectado exitosamente a Hyundai TV ({self.tv_ip}:{TV_PORT})")

                    # Register
                    reg_payload = {
                        "type": "register",
                        "id": "reg_web",
                        "payload": {
                            "forcePairing": False,
                            "pairingType": "PROMPT",
                            "client-key": self.client_key,
                            "manifest": {
                                "appVersion": "1.0",
                                "manifestVersion": 1,
                                "permissions": [
                                    "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE",
                                    "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_TEXT",
                                    "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS",
                                    "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS"
                                ]
                            }
                        }
                    }
                    await ws.send(json.dumps(reg_payload))

                    # Request pointer socket & volume subscription
                    await asyncio.sleep(0.3)
                    await self.send_request("ssap://com.webos.service.networkinput/getPointerInputSocket")
                    await self.send_request("ssap://audio/getVolume")

                    # Message loop
                    async for message in ws:
                        await self.handle_message(message)

            except Exception as e:
                self.connected = False
                self.main_ws = None
                self.pointer_ws = None
                print(f"[!] Desconectado de la TV ({e}). Reconectando en 4s...")
                await asyncio.sleep(4)

    async def handle_message(self, message):
        try:
            data = json.loads(message)
            msg_type = data.get("type")
            payload = data.get("payload", {})

            if msg_type == "registered":
                self.client_key = payload.get("client-key", self.client_key)
                self.connected = True

            elif msg_type == "response":
                if "socketPath" in payload:
                    socket_path = payload["socketPath"]
                    asyncio.create_task(self.connect_pointer(socket_path))

                if "volumeStatus" in payload:
                    vs = payload["volumeStatus"]
                    self.volume = vs.get("volume", self.volume)
                    self.muted = vs.get("muteStatus", self.muted)
                elif "volume" in payload:
                    self.volume = payload.get("volume", self.volume)
                    self.muted = payload.get("muted", self.muted)

        except Exception as e:
            print(f"[!] Error procesando mensaje de TV: {e}")

    async def connect_pointer(self, socket_path):
        try:
            async with websockets.connect(socket_path, ssl=self.ssl_ctx) as ws:
                self.pointer_ws = ws
                print("[*] Pointer WebSocket abierto para pulsación ultra-rápida de botones")
                async for _ in ws:
                    pass
        except Exception as e:
            self.pointer_ws = None

    async def send_request(self, uri, payload=None):
        if not self.main_ws:
            return
        self.req_id += 1
        req = {
            "id": f"req_{self.req_id}",
            "type": "request",
            "uri": uri
        }
        if payload is not None:
            req["payload"] = payload
        try:
            await self.main_ws.send(json.dumps(req))
        except Exception as e:
            print(f"[!] Error enviando request {uri}: {e}")

    async def send_key(self, key):
        if self.pointer_ws:
            try:
                cmd = f"type:button\nname:{key}\n\n"
                await self.pointer_ws.send(cmd)
                return
            except Exception:
                self.pointer_ws = None

        await self.send_request("ssap://system/simulateButton", {"key": key})

    async def send_command(self, action):
        key_map = {
            "UP": "UP",
            "DOWN": "DOWN",
            "LEFT": "LEFT",
            "RIGHT": "RIGHT",
            "OK": "ENTER",
            "BACK": "BACK",
            "HOME": "HOME",
            "MENU": "MENU",
            "EXIT": "EXIT",
            "RED": "RED",
            "GREEN": "GREEN",
            "YELLOW": "YELLOW",
            "BLUE": "BLUE"
        }

        if action in key_map:
            await self.send_key(key_map[action])
        elif action.startswith("NUM_"):
            digit = action.split("_")[1]
            await self.send_key(digit)
        elif action == "POWER":
            await self.send_request("ssap://system/turnOff")
        elif action == "VOL_UP":
            await self.send_request("ssap://audio/volumeUp")
            await self.send_request("ssap://audio/getVolume")
        elif action == "VOL_DOWN":
            await self.send_request("ssap://audio/volumeDown")
            await self.send_request("ssap://audio/getVolume")
        elif action == "MUTE":
            self.muted = not self.muted
            await self.send_request("ssap://audio/setMute", {"mute": self.muted})
            await self.send_request("ssap://audio/getVolume")
        elif action == "CH_UP":
            await self.send_request("ssap://tv/channelUp")
        elif action == "CH_DOWN":
            await self.send_request("ssap://tv/channelDown")
        elif action == "PLAY":
            await self.send_request("ssap://media.controls/play")
        elif action == "PAUSE":
            await self.send_request("ssap://media.controls/pause")
        elif action == "REWIND":
            await self.send_request("ssap://media.controls/rewind")
        elif action == "FAST_FORWARD":
            await self.send_request("ssap://media.controls/fastForward")

    async def launch_app(self, app_id):
        await self.send_request("ssap://system.launcher/launch", {"id": app_id})

    async def switch_input(self, input_id):
        await self.send_request("ssap://tv/switchInput", {"inputId": input_id})

    async def insert_text(self, text, enter=False):
        await self.send_request("ssap://com.webos.service.ime/insertText", {"text": text, "replace": 0})
        if enter:
            await asyncio.sleep(0.3)
            await self.send_request("ssap://com.webos.service.ime/sendEnterKey")

    def get_status(self):
        return {
            "connected": self.connected,
            "tvIp": self.tv_ip,
            "volume": self.volume,
            "muted": self.muted,
            "mode": "WIFI"
        }

tv_client = WebOsClient()

def get_html_content():
    try:
        with open(HTML_FILE_PATH, "r", encoding="utf-8") as f:
            return f.read().encode("utf-8")
    except Exception:
        return b"<html><body><h1>Hyundai Remote</h1><p>Archivo HTML no encontrado</p></body></html>"

cached_html = get_html_content()

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

# Async HTTP Server Handler
async def handle_http_request(reader, writer):
    try:
        data = await reader.read(4096)
        if not data:
            writer.close()
            return

        request_text = data.decode("utf-8", errors="ignore")
        lines = request_text.split("\r\n")
        if not lines:
            writer.close()
            return

        request_line = lines[0].split(" ")
        if len(request_line) < 2:
            writer.close()
            return

        method = request_line[0].upper()
        path = request_line[1]

        # Extract body if POST
        body = ""
        if "\r\n\r\n" in request_text:
            body = request_text.split("\r\n\r\n", 1)[1]

        # Route
        if method == "OPTIONS":
            resp = (
                b"HTTP/1.1 204 No Content\r\n"
                b"Access-Control-Allow-Origin: *\r\n"
                b"Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                b"Access-Control-Allow-Headers: Content-Type\r\n"
                b"Connection: close\r\n\r\n"
            )
            writer.write(resp)
            await writer.drain()

        elif method in ("GET", "HEAD") and (path == "/" or path.startswith("/index")):
            header = (
                f"HTTP/1.1 200 OK\r\n"
                f"Content-Type: text/html; charset=utf-8\r\n"
                f"Content-Length: {len(cached_html)}\r\n"
                f"Access-Control-Allow-Origin: *\r\n"
                f"Connection: close\r\n\r\n"
            ).encode("utf-8")
            if method == "HEAD":
                writer.write(header)
            else:
                writer.write(header + cached_html)
            await writer.drain()

        elif method in ("GET", "HEAD") and (path.startswith("/direct") or path.startswith("/download")):
            try:
                with open(DIRECT_HTML_PATH, "rb") as f:
                    direct_bytes = f.read()
            except Exception:
                direct_bytes = b"Archivo no encontrado"
            
            disposition = 'attachment; filename="Hyundai_TV_Directo.html"' if "download" in path else 'inline'
            header = (
                f"HTTP/1.1 200 OK\r\n"
                f"Content-Type: text/html; charset=utf-8\r\n"
                f"Content-Length: {len(direct_bytes)}\r\n"
                f"Content-Disposition: {disposition}\r\n"
                f"Access-Control-Allow-Origin: *\r\n"
                f"Connection: close\r\n\r\n"
            ).encode("utf-8")
            if method == "HEAD":
                writer.write(header)
            else:
                writer.write(header + direct_bytes)
            await writer.drain()

        elif method == "GET" and path.startswith("/api/status"):
            status_json = json.dumps(tv_client.get_status()).encode("utf-8")
            header = (
                f"HTTP/1.1 200 OK\r\n"
                f"Content-Type: application/json; charset=utf-8\r\n"
                f"Content-Length: {len(status_json)}\r\n"
                f"Access-Control-Allow-Origin: *\r\n"
                f"Connection: close\r\n\r\n"
            ).encode("utf-8")
            writer.write(header + status_json)
            await writer.drain()

        elif method == "POST" and path.startswith("/api/command"):
            try:
                payload = json.loads(body) if body else {}
                action = payload.get("action", "")
                if action:
                    await tv_client.send_command(action)
            except Exception as e:
                print(f"[!] Error en command: {e}")

            status_json = json.dumps(tv_client.get_status()).encode("utf-8")
            header = (
                f"HTTP/1.1 200 OK\r\n"
                f"Content-Type: application/json; charset=utf-8\r\n"
                f"Content-Length: {len(status_json)}\r\n"
                f"Access-Control-Allow-Origin: *\r\n"
                f"Connection: close\r\n\r\n"
            ).encode("utf-8")
            writer.write(header + status_json)
            await writer.drain()

        elif method == "POST" and path.startswith("/api/launch"):
            try:
                payload = json.loads(body) if body else {}
                app_id = payload.get("appId", "")
                if app_id:
                    await tv_client.launch_app(app_id)
            except Exception as e:
                print(f"[!] Error en launch: {e}")

            status_json = json.dumps(tv_client.get_status()).encode("utf-8")
            header = (
                f"HTTP/1.1 200 OK\r\n"
                f"Content-Type: application/json; charset=utf-8\r\n"
                f"Content-Length: {len(status_json)}\r\n"
                f"Access-Control-Allow-Origin: *\r\n"
                f"Connection: close\r\n\r\n"
            ).encode("utf-8")
            writer.write(header + status_json)
            await writer.drain()

        elif method == "POST" and path.startswith("/api/input"):
            try:
                payload = json.loads(body) if body else {}
                input_id = payload.get("inputId", "")
                if input_id:
                    await tv_client.switch_input(input_id)
            except Exception as e:
                print(f"[!] Error en input: {e}")

            status_json = json.dumps(tv_client.get_status()).encode("utf-8")
            header = (
                f"HTTP/1.1 200 OK\r\n"
                f"Content-Type: application/json; charset=utf-8\r\n"
                f"Content-Length: {len(status_json)}\r\n"
                f"Access-Control-Allow-Origin: *\r\n"
                f"Connection: close\r\n\r\n"
            ).encode("utf-8")
            writer.write(header + status_json)
            await writer.drain()

        elif method == "POST" and path.startswith("/api/text"):
            try:
                payload = json.loads(body) if body else {}
                text = payload.get("text", "")
                enter = payload.get("enter", False)
                if text:
                    await tv_client.insert_text(text, enter)
            except Exception as e:
                print(f"[!] Error en text: {e}")

            status_json = json.dumps(tv_client.get_status()).encode("utf-8")
            header = (
                f"HTTP/1.1 200 OK\r\n"
                f"Content-Type: application/json; charset=utf-8\r\n"
                f"Content-Length: {len(status_json)}\r\n"
                f"Access-Control-Allow-Origin: *\r\n"
                f"Connection: close\r\n\r\n"
            ).encode("utf-8")
            writer.write(header + status_json)
            await writer.drain()

        else:
            resp = b"HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\n\r\nNot Found"
            writer.write(resp)
            await writer.drain()

    except Exception as e:
        print(f"[!] Error en request HTTP: {e}")
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass

async def main():
    import argparse
    parser = argparse.ArgumentParser(description="Hyundai TV Web Remote Server")
    parser.add_argument("--port", type=int, default=HTTP_PORT, help="Puerto HTTP (por defecto 8080)")
    parser.add_argument("--tv", type=str, default=TV_IP_DEFAULT, help="IP del televisor Hyundai (por defecto 192.168.1.3)")
    args = parser.parse_args()

    tv_client.tv_ip = args.tv

    local_ip = get_local_ip()

    print("=" * 60)
    print(" 📺 SERVIDOR DE CONTROL REMOTO HYUNDAI TV (WI-FI WEB)")
    print("=" * 60)
    print(f" -> Televisor objetivo: {args.tv}:{TV_PORT}")
    print(f" -> Servidor Web iniciado en el puerto: {args.port}")
    print("\n 🌐 Abre cualquiera de estos enlaces en tu navegador:")
    print(f"    • En este dispositivo:  http://localhost:{args.port}")
    print(f"    • En tu PC, tablet u otro móvil en la misma Wi-Fi:")
    print(f"      http://{local_ip}:{args.port}")
    print("=" * 60)

    # Start TV connector background task
    asyncio.create_task(tv_client.connect())

    # Start HTTP server
    server = await asyncio.start_server(handle_http_request, "0.0.0.0", args.port)

    async with server:
        await server.serve_forever()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[!] Servidor detenido por el usuario.")
