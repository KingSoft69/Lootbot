import json
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path

from lootlearner import targets
from lootlearner.dashboard import Dashboard, Zenith
from lootlearner.server import Learner
from tests.test_learner import config


@unittest.skipIf(sys.platform.startswith("win"), "uses a shell script as the fake launcher")
class DashboardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        zen = Path(self.tmp.name) / "zen"
        zen.mkdir()
        launch = zen / "launch"
        launch.write_text('#!/bin/sh\necho "fake zenith up"\nwhile read line; do echo "got: $line"; '
                          '[ "$line" = "shutdown" ] && exit 0; done\n')
        os.chmod(launch, 0o755)
        self.learner = Learner(Path(self.tmp.name) / "data", config=config(), log=lambda *_: None)
        self.dash = Dashboard(self.learner, Zenith(zen, log=lambda *_: None))

    def tearDown(self):
        self.dash.zenith.shutdown()
        self.learner.db.close()
        self.tmp.cleanup()

    def wait_for(self, text):
        for _ in range(50):
            if any(text in l["t"] for l in self.dash.zenith.since(0)):
                return True
            time.sleep(0.1)
        return False

    def test_runs_zenith_and_relays_commands(self):
        self.assertEqual(self.dash.post("/ui/zenith/start", {})["msg"], "starting")
        self.assertTrue(self.wait_for("fake zenith up"))
        self.assertEqual(self.dash.post("/ui/zenith/cmd", {"cmd": "loot status"})["msg"], "sent")
        self.assertTrue(self.wait_for("got: loot status"))
        self.dash.post("/ui/zenith/stop", {})
        self.assertTrue(self.wait_for("stopped (exit 0)"))
        self.assertFalse(self.dash.zenith.want)

    def test_live_view_keeps_the_freshest(self):
        self.assertEqual(self.dash.view(), {})
        self.learner.views["BotA"] = {"bot": "BotA", "x": 1, "receivedAt": 100}
        self.learner.views["BotB"] = {"bot": "BotB", "x": 2, "receivedAt": 200}
        self.assertEqual(self.dash.view()["bot"], "BotB")

    def test_state_and_target_buttons(self):
        tid = targets.add(self.learner.db, 10, 20, label="High priority - X10 Z20")
        s = self.dash.state()
        self.assertEqual(s["targets"]["counts"], {"open": 1})
        self.assertTrue(s["zenith"]["installed"])
        json.dumps(s)
        self.dash.post(f"/ui/targets/{tid}/skip", {})
        self.assertEqual(self.dash.state()["targets"]["counts"], {"skipped": 1})
        self.dash.post("/ui/targets/add", {"x": 5, "z": 6})
        self.assertEqual(self.dash.state()["targets"]["counts"].get("open"), 1)


if __name__ == "__main__":
    unittest.main()
