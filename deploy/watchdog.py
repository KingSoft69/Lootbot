#!/usr/bin/env python3
"""Puts a ZenithProxy instance back on its previous LootBot jar when the new one went wrong.

The plugin keeps the jar it replaced as LootBot-<old>.jar.bak. A bad jar can stop the plugin from
loading at all, and then the learner has no way to talk the bot back down, so this runs from cron
on each droplet (every 10 minutes) and, for every instance it knows:

  - the jar in plugins/ is a release the learner marked bad, or
  - the bot has not checked in for silentMinutes while running a canary jar,

restores the .bak and restarts the proxy by typing 'update' into its tmux session.

watchdog.json, next to this file:
{
  "learner": "http://127.0.0.1:25700",
  "operatorToken": "...",
  "silentMinutes": 45,
  "instances": [
    {"bot": "AccountName", "plugins": "/root/zenith1/plugins", "tmux": "zenith1"}
  ]
}
crontab -e:  */10 * * * * /usr/bin/python3 /root/lootbot/deploy/watchdog.py >> /root/lootbot/watchdog.log 2>&1
"""

import json
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path


def status(cfg):
    req = urllib.request.Request(cfg["learner"].rstrip("/") + "/api/status",
                                 headers={"Authorization": "Bearer " + cfg["operatorToken"]})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read())


def current_jar(plugins):
    jars = sorted(Path(plugins).glob("LootBot-*.jar"))
    return jars[-1] if jars else None


def version_of(path):
    m = re.match(r"LootBot-(.+?)\.jar", path.name)
    return m.group(1) if m else None


def restore(inst, jar, log):
    baks = sorted(Path(inst["plugins"]).glob("LootBot-*.jar.bak"))
    if not baks:
        log(f"{inst['bot']}: no .bak to go back to")
        return False
    bak = baks[-1]
    if jar is not None:
        jar.rename(jar.with_name(jar.name + ".bad"))
    bak.rename(bak.with_name(bak.name[: -len(".bak")]))
    subprocess.run(["tmux", "send-keys", "-t", inst["tmux"], "update", "Enter"], check=False)
    log(f"{inst['bot']}: restored {bak.name[:-4]} and restarted {inst['tmux']}")
    return True


def main():
    here = Path(__file__).resolve().parent
    cfg = json.loads((here / "watchdog.json").read_text())

    def log(msg):
        print(time.strftime("%Y-%m-%d %H:%M:%S"), msg, flush=True)

    try:
        st = status(cfg)
    except Exception as e:
        log(f"learner not reachable: {e}")
        return 1
    rel = {r["version"]: r["status"] for r in st.get("releases", [])}
    seen = {b["name"]: b for b in st.get("bots", [])}
    silent = float(cfg.get("silentMinutes", 45))
    for inst in cfg.get("instances", []):
        jar = current_jar(inst["plugins"])
        v = version_of(jar) if jar else None
        bot = seen.get(inst["bot"])
        quiet = bot is None or bot.get("minutesAgo", 1e9) > silent
        if v and rel.get(v) == "bad":
            restore(inst, jar, log)
        elif v and rel.get(v) == "canary" and quiet and bot is not None and bot.get("version") != v:
            # Never came back on the canary jar: it does not load.
            restore(inst, jar, log)
    return 0


if __name__ == "__main__":
    sys.exit(main())
