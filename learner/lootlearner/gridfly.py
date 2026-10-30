"""Targets from the GridFly server's finds, and looted marks back to it.

The learner logs in to the GridFly server as an operator UI (the same websocket the command app
uses), asks for the best unlooted spots (the Finds tab's "best" query) and keeps each spot with
something to loot as a target. When a bot has been through a spot, the spot's finds are marked
looted on the GridFly server with a note of what was taken, so the map and the Discord posts
show it and GridFly stops offering it.

Standard library only: a minimal websocket client is included.
"""

import base64
import json
import math
import os
import socket
import ssl
import struct
import time
import urllib.parse
import uuid

from . import targets as targets_mod

PROTOCOL_VERSION = 1
# A spot this close to a known target is the same base (finds get added to a spot over time).
SAME_SPOT = 48


# ---------------------------------------------------------------- websocket

class WebSocket:
    def __init__(self, url, timeout=30):
        u = urllib.parse.urlparse(url)
        if u.scheme not in ("ws", "wss"):
            raise ValueError(f"not a websocket url: {url}")
        port = u.port or (443 if u.scheme == "wss" else 80)
        raw = socket.create_connection((u.hostname, port), timeout=timeout)
        if u.scheme == "wss":
            raw = ssl.create_default_context().wrap_socket(raw, server_hostname=u.hostname)
        self.sock = raw
        self.buf = b""
        key = base64.b64encode(os.urandom(16)).decode()
        path = (u.path or "/") + (f"?{u.query}" if u.query else "")
        host = u.hostname + (f":{u.port}" if u.port else "")
        self.sock.sendall((f"GET {path} HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                           f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        head = self._read_until(b"\r\n\r\n")
        status = head.split(b"\r\n", 1)[0]
        if b" 101 " not in status + b" ":
            raise ConnectionError(f"websocket handshake refused: {status.decode(errors='replace')}")

    def _read_until(self, marker):
        while marker not in self.buf:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("connection closed")
            self.buf += chunk
        i = self.buf.index(marker) + len(marker)
        out, self.buf = self.buf[:i], self.buf[i:]
        return out

    def _read(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(max(65536, n - len(self.buf)))
            if not chunk:
                raise ConnectionError("connection closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def _send_frame(self, opcode, payload):
        head = bytes([0x80 | opcode])
        n = len(payload)
        if n < 126:
            head += bytes([0x80 | n])
        elif n < 65536:
            head += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            head += bytes([0x80 | 127]) + struct.pack(">Q", n)
        mask = os.urandom(4)
        body = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(head + mask + body)

    def send(self, text):
        self._send_frame(0x1, text.encode())

    def recv(self):
        """The next text message (pings answered, fragments joined)."""
        parts = []
        while True:
            b0, b1 = self._read(2)
            opcode, fin = b0 & 0x0F, b0 & 0x80
            n = b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._read(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._read(8))[0]
            mask = self._read(4) if b1 & 0x80 else None
            data = self._read(n)
            if mask:
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
            if opcode == 0x8:
                raise ConnectionError("closed by the server")
            if opcode == 0x9:
                self._send_frame(0xA, data)
                continue
            if opcode == 0xA:
                continue
            parts.append(data)
            if fin:
                return b"".join(parts).decode()

    def close(self):
        try:
            self._send_frame(0x8, b"")
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


# ---------------------------------------------------------------- GridFly

class GridFlyClient:
    def __init__(self, url, token, timeout=60):
        self.ws = WebSocket(url, timeout=timeout)
        self.ws.send(json.dumps({"type": "ui_hello", "token": token, "clientName": "LootBot learner",
                                 "protocolVersion": PROTOCOL_VERSION}))
        msg = json.loads(self.ws.recv())
        if msg.get("type") == "rejected":
            self.ws.close()
            raise PermissionError(f"GridFly refused the login: {msg.get('reason')}")
        if msg.get("type") != "welcome":
            self.ws.close()
            raise ConnectionError(f"GridFly answered {msg.get('type')} to the login")
        self.server_version = msg.get("serverVersion")

    def best_sites(self, limit=300, dimension=None):
        rid = uuid.uuid4().hex
        query = {"mode": "BEST", "kinds": [], "rank": "OVERALL", "sort": "NEWEST", "limit": int(limit),
                 "offset": 0, "looted": False}
        if dimension:
            query["dimension"] = dimension
        self.ws.send(json.dumps({"type": "query_finds", "requestId": rid, "query": query}))
        deadline = time.time() + 120
        while time.time() < deadline:
            msg = json.loads(self.ws.recv())
            if msg.get("type") == "finds_result" and msg.get("requestId") == rid:
                if msg.get("error"):
                    raise RuntimeError(f"GridFly finds query failed: {msg['error']}")
                return msg.get("sites") or []
        raise TimeoutError("GridFly did not answer the finds query")

    def set_looted(self, ids, note):
        self.ws.send(json.dumps({"type": "set_looted", "ids": list(ids), "looted": True, "note": note[:500]}))

    def close(self):
        self.ws.close()


DIMS = {"OVERWORLD": "overworld", "NETHER": "nether", "END": "end"}


def lootable(site):
    """A spot worth a trip: something with containers in it, not only signs, pearls or gateways."""
    return any(int(site.get(k) or 0) > 0 for k in ("shulkers", "chests", "chestGroups", "netherite"))


def kind_of(site):
    if int(site.get("netherite") or 0) > 0:
        return "netherite"
    if int(site.get("shulkers") or 0) > 0:
        return "shulkers"
    return "chests"


def describe(site):
    bits = []
    for key, word in (("netherite", "netherite"), ("shulkers", "shulkers"), ("chests", "chests"),
                      ("enderChests", "ender chests"), ("crafters", "crafters")):
        n = int(site.get(key) or 0)
        if n:
            bits.append(f"{n} {word}")
    hl = (site.get("highlight") or "").strip()
    return (hl + "; " if hl else "") + ", ".join(bits)


def import_sites(db, sites, min_score=0.0):
    """Adds or refreshes a target per lootable spot. Returns (added, refreshed)."""
    added = refreshed = 0
    for s in sites:
        if not lootable(s) or float(s.get("score") or 0) < min_score:
            continue
        if int(s.get("looted") or 0) > 0 and int(s.get("looted") or 0) >= len(s.get("ids") or []):
            continue
        dim = DIMS.get(str(s.get("dimension") or "OVERWORLD").upper(), "overworld")
        x, z = int(s["x"]), int(s["z"])
        y = s.get("y")
        key = s.get("stashId") or (s.get("ids") or [None])[0] or f"{dim}:{x}:{z}"
        ids = json.dumps(s.get("ids") or [])
        weight = float(s.get("score") or 1.0)
        label = describe(s)
        source = "gridfly:" + kind_of(s)
        existing = db.one("SELECT id FROM targets WHERE ext_key = ?", (key,))
        if not existing:
            near = db.all("SELECT id, x, z FROM targets WHERE dim = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?",
                          (dim, x - SAME_SPOT, x + SAME_SPOT, z - SAME_SPOT, z + SAME_SPOT))
            existing = min(near, key=lambda t: math.hypot(t["x"] - x, t["z"] - z)) if near else None
        if existing:
            with db.tx() as c:
                c.execute("UPDATE targets SET ext_key = ?, ext_ids = ?, weight = ?, label = ? WHERE id = ?",
                          (key, ids, weight, label, existing["id"]))
            refreshed += 1
            continue
        tid = targets_mod.add(db, x, z, y, dim, label, source)
        with db.tx() as c:
            c.execute("UPDATE targets SET ext_key = ?, ext_ids = ?, weight = ? WHERE id = ?", (key, ids, weight, tid))
        added += 1
    return added, refreshed


def queue_looted(db, target_id, note):
    """Remembers that a GridFly spot was looted; the next sync tells the GridFly server."""
    t = db.one("SELECT ext_ids FROM targets WHERE id = ?", (target_id,))
    if not t or not t["ext_ids"] or t["ext_ids"] == "[]":
        return False
    with db.tx() as c:
        c.execute("UPDATE targets SET ext_pending = ? WHERE id = ?", (note, target_id))
    return True


class Sync:
    def __init__(self, db, config, log=print, connect=None):
        self.db = db
        self.config = config  # the "gridfly" section
        self.log = log
        self.connect = connect or (lambda: GridFlyClient(config["url"], config.get("token", "")))
        self.last = 0.0

    def due(self, now):
        return bool(self.config.get("url")) and now - self.last >= float(self.config.get("syncMinutes", 30)) * 60

    def run(self, now=None):
        self.last = now or time.time()
        client = self.connect()
        try:
            marked = 0
            if self.config.get("markLooted", True):
                for t in self.db.all("SELECT id, ext_ids, ext_pending FROM targets WHERE ext_pending IS NOT NULL"):
                    client.set_looted(json.loads(t["ext_ids"]), t["ext_pending"])
                    with self.db.tx() as c:
                        c.execute("UPDATE targets SET ext_pending = NULL WHERE id = ?", (t["id"],))
                    marked += 1
            sites = client.best_sites(int(self.config.get("limit", 300)))
            added, refreshed = import_sites(self.db, sites, float(self.config.get("minScore", 0)))
        finally:
            client.close()
        msg = f"GridFly: {len(sites)} spots, {added} new target(s), {refreshed} refreshed, {marked} marked looted"
        self.log(msg)
        if added or marked:
            self.db.event(None, "gridfly", msg)
        return added, refreshed, marked
