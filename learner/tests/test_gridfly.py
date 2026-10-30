import base64
import hashlib
import json
import random
import socket
import struct
import tempfile
import threading
import unittest

from lootlearner import gridfly, targets
from lootlearner.server import Learner
from tests.test_learner import config, episode

SITES = [
    {"dimension": "OVERWORLD", "x": 1000, "y": 64, "z": 2000, "shulkers": 3, "chests": 12, "chestGroups": 1,
     "netherite": 0, "score": 42, "ids": ["w1", "w2"], "stashId": "s1", "highlight": "", "looted": 0},
    {"dimension": "NETHER", "x": 50, "y": 120, "z": 60, "shulkers": 0, "chests": 0, "chestGroups": 0,
     "netherite": 0, "signs": 4, "score": 0, "ids": ["w3"], "stashId": None, "looted": 0},  # signs only: skipped
    {"dimension": "OVERWORLD", "x": -500, "y": 30, "z": 900, "shulkers": 0, "chests": 0, "chestGroups": 0,
     "netherite": 2, "score": 200, "ids": ["w4"], "stashId": None, "highlight": "2 netherite blocks", "looted": 0},
]


class FakeGridFly:
    """A websocket server speaking just enough of the GridFly operator protocol."""

    def __init__(self, token="op-token"):
        self.token = token
        self.received = []
        self.srv = socket.socket()
        self.srv.bind(("127.0.0.1", 0))
        self.srv.listen()
        self.port = self.srv.getsockname()[1]
        threading.Thread(target=self.serve, daemon=True).start()

    def url(self):
        return f"ws://127.0.0.1:{self.port}/ui"

    def serve(self):
        while True:
            try:
                conn, _ = self.srv.accept()
            except OSError:
                return
            threading.Thread(target=self.handle, args=(conn,), daemon=True).start()

    def handle(self, conn):
        buf = b""
        while b"\r\n\r\n" not in buf:
            buf += conn.recv(4096)
        key = [l.split(b": ", 1)[1] for l in buf.split(b"\r\n") if l.lower().startswith(b"sec-websocket-key")][0]
        accept = base64.b64encode(hashlib.sha1(key + b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest()).decode()
        conn.sendall(f"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n".encode())
        f = conn.makefile("rb")

        def read():
            b0, b1 = f.read(2)
            n = b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", f.read(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", f.read(8))[0]
            mask = f.read(4)
            data = bytes(b ^ mask[i % 4] for i, b in enumerate(f.read(n)))
            return b0 & 0x0F, data

        def send(obj, opcode=1):
            data = json.dumps(obj).encode() if opcode == 1 else obj
            n = len(data)
            head = bytes([0x80 | opcode]) + (bytes([n]) if n < 126 else bytes([126]) + struct.pack(">H", n) if n < 65536
                                             else bytes([127]) + struct.pack(">Q", n))
            conn.sendall(head + data)

        try:
            while True:
                op, data = read()
                if op == 8:
                    return
                msg = json.loads(data)
                self.received.append(msg)
                if msg["type"] == "ui_hello":
                    if msg["token"] != self.token:
                        send({"type": "rejected", "reason": "bad token"})
                        return
                    send({"type": "welcome", "serverName": "test", "serverVersion": "0.19.0", "protocolVersion": 1})
                    send(b"hi", opcode=9)  # a ping the client must answer
                    send({"type": "snapshot", "bots": [], "waypoints": [{"x": i} for i in range(3000)]})  # big frame
                elif msg["type"] == "query_finds":
                    send({"type": "log", "line": "something else first"})
                    send({"type": "finds_result", "requestId": msg["requestId"], "sites": SITES, "finds": [], "total": 3})
        except (OSError, ValueError, json.JSONDecodeError):
            return
        finally:
            conn.close()

    def close(self):
        self.srv.close()


class GridFlyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.fake = FakeGridFly()
        cfg = config()
        cfg["gridfly"] = {"url": self.fake.url(), "token": "op-token", "syncMinutes": 30, "limit": 300, "minScore": 0, "markLooted": True}
        self.learner = Learner(self.tmp.name, config=cfg, rng=random.Random(1), log=lambda *_: None)

    def tearDown(self):
        self.fake.close()
        self.learner.db.close()
        self.tmp.cleanup()

    def test_sync_imports_lootable_spots_and_marks_looted(self):
        added, refreshed, marked = self.learner.gridfly.run()
        self.assertEqual((added, refreshed, marked), (2, 0, 0))
        rows = {r["source"]: r for r in self.learner.db.all("SELECT * FROM targets")}
        self.assertEqual(set(rows), {"gridfly:shulkers", "gridfly:netherite"})
        self.assertEqual(json.loads(rows["gridfly:shulkers"]["ext_ids"]), ["w1", "w2"])
        # Again: the same spots are refreshed, not doubled.
        self.assertEqual(self.learner.gridfly.run()[:2], (0, 2))
        self.assertEqual(len(self.learner.db.all("SELECT id FROM targets")), 2)
        # The bot loots the netherite spot: the next sync marks its finds looted on GridFly.
        w = self.learner.next({"bot": "BotA", "version": "0.1.0", "state": {"x": -500, "z": 900, "dim": "overworld"}})
        self.assertEqual(w["target"]["x"], -500)
        self.learner.episode(episode(w, items={"netherite_block": 2}))
        self.assertEqual(self.learner.gridfly.run()[2], 1)
        looted = [m for m in self.fake.received if m["type"] == "set_looted"]
        self.assertEqual(looted[0]["ids"], ["w4"])
        self.assertIn("netherite_block", looted[0]["note"])
        self.assertEqual(self.learner.gridfly.run()[2], 0)  # sent once

    def test_high_score_spot_is_preferred_at_equal_distance(self):
        targets.add(self.learner.db, 100, 0, source="gridfly:shulkers")
        hi = targets.add(self.learner.db, -100, 0, source="gridfly:shulkers")
        with self.learner.db.tx() as c:
            c.execute("UPDATE targets SET weight = 500 WHERE id = ?", (hi,))
        w = self.learner.next({"bot": "BotA", "version": "0.1.0", "state": {"x": 0, "z": 0}})
        self.assertEqual(w["target"]["id"], hi)

    def test_bad_token(self):
        bad = gridfly.Sync(self.learner.db, {"url": self.fake.url(), "token": "wrong"}, log=lambda *_: None)
        with self.assertRaises(PermissionError):
            bad.run()


if __name__ == "__main__":
    unittest.main()
