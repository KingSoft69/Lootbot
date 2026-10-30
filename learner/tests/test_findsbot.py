import json
import random
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from lootlearner.server import Learner
from tests.test_learner import config, episode


class FakeFindsBot:
    def __init__(self, key="k"):
        self.key = key
        self.finds = [
            {"id": "111", "title": "big stash", "priority": "High", "x": 1500, "z": 0, "url": "https://discord/1"},
            {"id": "222", "title": "chests", "priority": "Low", "x": 100, "z": 100, "url": "https://discord/2"},
        ]
        self.visited = []
        bot = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _send(self, code, body):
                data = json.dumps(body).encode()
                self.send_response(code)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                if self.headers.get("X-Finds-Key") != bot.key:
                    return self._send(401, {"error": "key"})
                if self.path == "/finds":
                    return self._send(200, {"finds": bot.finds})
                self._send(404, {})

            def do_POST(self):
                if self.headers.get("X-Finds-Key") != bot.key:
                    return self._send(401, {"error": "key"})
                n = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(n) or b"{}")
                fid = self.path.split("/")[2]
                if not any(f["id"] == fid for f in bot.finds):
                    return self._send(404, {"error": "closed"})
                bot.visited.append((fid, body.get("player")))
                bot.finds = [f for f in bot.finds if f["id"] != fid]
                self._send(200, {"ok": True})

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}"

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class FindsBotTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.bot = FakeFindsBot()
        cfg = config()
        cfg["findsBot"] = {"url": self.bot.url, "key": "k", "syncMinutes": 5, "markVisited": True}
        self.learner = Learner(self.tmp.name, config=cfg, rng=random.Random(1), log=lambda *_: None)

    def tearDown(self):
        self.bot.close()
        self.learner.db.close()
        self.tmp.cleanup()

    def test_finds_become_targets_and_get_marked_visited(self):
        self.assertEqual(self.learner.findsbot.run()[:3], (2, 0, 0))
        self.assertEqual(self.learner.findsbot.run()[:3], (0, 2, 0))
        # High priority wins over a closer Low one.
        w = self.learner.next({"bot": "BotA", "version": "0.1.0", "state": {"x": 0, "z": 0, "dim": "overworld"}})
        self.assertEqual((w["target"]["x"], w["target"]["z"]), (1500, 0))
        self.learner.episode(episode(w, items={"in:diamond": 64}))
        self.assertEqual(self.learner.findsbot.run()[3], 1)
        self.assertEqual(self.bot.visited[0][0], "111")
        self.assertIn("LootBot (BotA)", self.bot.visited[0][1])
        self.assertEqual(self.learner.findsbot.run()[3], 0)  # once only

    def test_find_closed_on_discord_leaves_the_queue(self):
        self.learner.findsbot.run()
        self.bot.finds = [f for f in self.bot.finds if f["id"] != "222"]  # a player marked it visited
        self.assertEqual(self.learner.findsbot.run()[2], 1)
        rows = {r["label"]: r["status"] for r in self.learner.db.all("SELECT label, status FROM targets")}
        self.assertEqual(rows["Low chests"], "closed")

    def test_wrong_key(self):
        self.learner.findsbot.config["key"] = "nope"
        with self.assertRaises(PermissionError):
            self.learner.findsbot.run()


if __name__ == "__main__":
    unittest.main()
