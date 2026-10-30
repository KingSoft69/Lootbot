import hashlib
import json
import random
import tempfile
import threading
import time
import unittest
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

from lootlearner import targets
from lootlearner.deploy import version_key
from lootlearner.server import DEFAULT_CONFIG, Learner, make_handler
from lootlearner.values import Values


def config():
    cfg = json.loads(json.dumps(DEFAULT_CONFIG))
    cfg["botToken"], cfg["operatorToken"] = "bot", "op"
    cfg["canary"]["episodes"] = 4
    return cfg


def episode(work, bot="BotA", version="0.1.0", outcome="looted", items=None, loot_s=120, travel_s=60, deaths=0, notes=None):
    return {
        "bot": bot, "version": version, "targetId": work["target"]["id"], "paramsId": work["paramsId"],
        "params": work["params"], "outcome": outcome, "seconds": loot_s + travel_s, "travelSeconds": travel_s,
        "lootSeconds": loot_s, "deaths": deaths, "items": items or {}, "failures": [], "notes": notes or [],
    }


class LearnerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.learner = Learner(self.tmp.name, config=config(), rng=random.Random(1), log=lambda *_: None)

    def tearDown(self):
        self.learner.db.close()
        self.tmp.cleanup()

    def state(self, x=0, z=0):
        return {"x": x, "z": z, "dim": "overworld"}

    def test_values_and_reward(self):
        v = Values.load()
        self.assertEqual(v.item("in:elytra"), v.item("elytra"))
        value, reward = v.reward({"items": {"diamond": 10}, "lootSeconds": 30, "travelSeconds": 30})
        self.assertAlmostEqual(value, 10)
        self.assertAlmostEqual(reward, 10 / 60 * 3600)
        # Travel counts only up to the cap, and a death costs points.
        _, far = v.reward({"items": {"diamond": 10}, "lootSeconds": 60, "travelSeconds": 100000})
        self.assertAlmostEqual(far, 10 / 660 * 3600)
        value, _ = v.reward({"items": {}, "deaths": 1})
        self.assertLess(value, 0)
        self.assertIn("elytra", v.wanted())
        self.assertNotIn("white_shulker_box", v.wanted())

    def test_no_targets_means_wait(self):
        w = self.learner.next({"bot": "BotA", "version": "0.1.0", "state": self.state()})
        self.assertNotIn("target", w)
        self.assertGreater(w["retrySeconds"], 0)

    def test_nearest_likely_target_and_outcomes(self):
        far = targets.add(self.learner.db, 50000, 50000)
        near = targets.add(self.learner.db, 100, 100)
        w = self.learner.next({"bot": "BotA", "version": "0.1.0", "state": self.state()})
        self.assertEqual(w["target"]["id"], near)
        self.assertIn("wanted", w["params"])
        # A second bot does not get the target the first one is on.
        w2 = self.learner.next({"bot": "BotB", "version": "0.1.0", "state": self.state()})
        self.assertEqual(w2["target"]["id"], far)
        r = self.learner.episode(episode(w, items={"in:diamond": 64}))
        self.assertEqual(r["target"], "done")
        self.assertGreater(r["value"], 0)
        r2 = self.learner.episode(episode(w2, bot="BotB", outcome="unreachable"))
        self.assertEqual(r2["target"], "open")
        t = self.learner.db.one("SELECT * FROM targets WHERE id = ?", (far,))
        self.assertGreater(t["not_before"], time.time())  # parked for a while

    def test_container_limit_keeps_target_open_then_gives_up(self):
        tid = targets.add(self.learner.db, 10, 10)
        for i in range(6):
            status = targets.record(self.learner.db, tid, "looted", 5, more_left=True)
        self.assertEqual(status, "done")
        tid2 = targets.add(self.learner.db, 20, 20)
        self.assertEqual(targets.record(self.learner.db, tid2, "no_containers", 0), "open")
        self.assertEqual(targets.record(self.learner.db, tid2, "no_containers", 0), "empty")

    def test_csv_import(self):
        p = Path(self.tmp.name) / "t.csv"
        p.write_text("X,Z,label,source\n100,200,base a,finds\n,5,bad,\n300,400,,\n", encoding="utf-8")
        self.assertEqual(targets.import_csv(self.learner.db, p), 2)
        self.assertEqual(targets.counts(self.learner.db), {"open": 2})

    def test_gridfly_import(self):
        import sqlite3
        p = Path(self.tmp.name) / "gridfly.db"
        src = sqlite3.connect(p)
        src.execute("CREATE TABLE stashes (id TEXT PRIMARY KEY, dim TEXT NOT NULL, x INTEGER, y INTEGER, z INTEGER)")
        src.executemany("INSERT INTO stashes VALUES (?, ?, ?, ?, ?)", [("a", "OVERWORLD", 1, 64, 2), ("b", "NETHER", 3, 100, 4)])
        src.commit()
        src.close()
        self.assertEqual(targets.import_gridfly(self.learner.db, p), 2)
        dims = {r["dim"] for r in self.learner.db.all("SELECT dim FROM targets")}
        self.assertEqual(dims, {"overworld", "nether"})

    def test_tuner_climbs_toward_better_settings(self):
        """Simulated bases where bigger searchRadius and maxContainers pay off, with heavy noise."""
        rng = random.Random(7)
        self.learner.tuner.explore = 0.4

        def true_rate(p):
            return 1000 + 8 * p["searchRadius"] + 4 * min(p["maxContainers"], 80) - (300 if p["order"] == "nearest" else 0)

        for i in range(500):
            targets.add(self.learner.db, i * 10, 0)
        default_rate = true_rate(self.learner.tuner.defaults())
        for _ in range(450):
            w = self.learner.next({"bot": "BotA", "version": "0.1.0", "state": self.state()})
            rate = true_rate(w["params"]) * rng.uniform(0.4, 1.6)
            # 'diamond' is 1 point: points per hour == diamonds over 600 s * 6
            diamonds = int(rate / 6)
            self.learner.episode(episode(w, items={"diamond": diamonds}, loot_s=540, travel_s=60))
        best = self.learner.tuner.params(self.learner.tuner.incumbent())
        self.assertGreater(true_rate(best), default_rate * 1.1)

    def test_canary_promotes_then_rolls_back(self):
        d = self.learner.deployer
        for bot in ("BotA", "BotB"):
            self.learner.next({"bot": bot, "version": "0.1.0", "state": self.state()})
        self.assertEqual(d.live()["version"], "0.1.0")  # adopted from the running bots
        for i in range(40):
            targets.add(self.learner.db, i * 10, 0)
        # Baseline on the live version.
        for _ in range(6):
            w = self.learner.next({"bot": "BotB", "version": "0.1.0", "state": self.state()})
            self.learner.episode(episode(w, bot="BotB", items={"diamond": 20}))
        d.add_release("0.1.1", b"jar-0.1.1")
        self.assertEqual(d.canary()["version"], "0.1.1")
        self.assertEqual(d.canary_bots(), ["BotA"])
        # The canary bot is told to install it; the other bot is not.
        w = self.learner.next({"bot": "BotA", "version": "0.1.0", "state": self.state()})
        self.assertEqual(w["version"], "0.1.1")
        self.assertEqual(w["jar"]["sha256"], hashlib.sha256(b"jar-0.1.1").hexdigest())
        self.assertNotIn("target", w)
        self.assertIn("target", self.learner.next({"bot": "BotB", "version": "0.1.0", "state": self.state()}))
        # Equal results on the canary: promoted.
        for _ in range(4):
            w = self.learner.next({"bot": "BotA", "version": "0.1.1", "state": self.state()})
            self.learner.episode(episode(w, version="0.1.1", items={"diamond": 20}))
        self.assertEqual(d.evaluate(), "promoted")
        self.assertEqual(d.live()["version"], "0.1.1")
        # Now everyone is told to move to it.
        self.assertEqual(self.learner.next({"bot": "BotB", "version": "0.1.0", "state": self.state()})["version"], "0.1.1")
        # A worse release is rolled back and the canary goes back to the live one.
        d.add_release("0.1.2", b"jar-0.1.2")
        for _ in range(4):
            w = self.learner.next({"bot": "BotA", "version": "0.1.2", "state": self.state()})
            self.learner.episode(episode(w, version="0.1.2", items={"diamond": 2}))
        self.assertEqual(d.evaluate(), "rolled back (reward)")
        self.assertEqual(self.learner.next({"bot": "BotA", "version": "0.1.2", "state": self.state()})["version"], "0.1.1")

    def test_silent_canary_is_rolled_back(self):
        d = self.learner.deployer
        self.learner.next({"bot": "BotA", "version": "0.1.0", "state": self.state()})
        d.add_release("0.2.0", b"x")
        self.assertEqual(d.evaluate(now=time.time() + 3 * 3600), "rolled back (silent)")
        self.assertIsNone(d.canary())

    def test_bot_that_cannot_update_still_gets_work(self):
        self.learner.next({"bot": "BotA", "version": "0.1.0", "state": self.state()})
        targets.add(self.learner.db, 5, 5)
        self.learner.deployer.add_release("0.1.1", b"x")
        st = dict(self.state(), updateFailed="0.1.1")
        self.assertIn("target", self.learner.next({"bot": "BotA", "version": "0.1.0", "state": st}))

    def test_version_order(self):
        self.assertGreater(version_key("0.10.0"), version_key("0.9.3"))

    def test_report(self):
        targets.add(self.learner.db, 1, 1)
        w = self.learner.next({"bot": "BotA", "version": "0.1.0", "state": self.state()})
        ep = episode(w, items={"in:elytra": 1})
        ep["failures"] = [{"reason": "path and open timed out"}]
        self.learner.episode(ep)
        md = self.learner.write_report()
        self.assertIn("path and open timed out", md)
        self.assertIn("elytra", md)
        self.assertTrue((Path(self.tmp.name) / "reports" / "latest.json").exists())


class HttpTest(unittest.TestCase):
    def test_round_trip(self):
        with tempfile.TemporaryDirectory() as tmp:
            learner = Learner(tmp, config=config(), log=lambda *_: None)
            targets.add(learner.db, 7, 7)
            httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(learner))
            threading.Thread(target=httpd.serve_forever, daemon=True).start()
            base = f"http://127.0.0.1:{httpd.server_address[1]}"

            def call(path, body=None, token="bot"):
                req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                             headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
                with urllib.request.urlopen(req) as r:
                    return r.read()

            try:
                w = json.loads(call("/api/next", {"bot": "BotA", "version": "0.1.0", "state": {"x": 0, "z": 0}}))
                self.assertEqual(w["target"]["x"], 7)
                r = json.loads(call("/api/episode", episode(w, items={"diamond": 3})))
                self.assertTrue(r["ok"])
                self.assertIn(b"LootBot report", call("/api/report", token="op"))
                with self.assertRaises(urllib.error.HTTPError) as e:
                    call("/api/report", token="bot")
                self.assertEqual(e.exception.code, 401)
            finally:
                httpd.shutdown()
                httpd.server_close()
                learner.db.close()


if __name__ == "__main__":
    unittest.main()
