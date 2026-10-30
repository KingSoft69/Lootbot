"""The local dashboard: one page that runs everything on this computer.

It starts and stops ZenithProxy (as a child process, its console shown on the page and commands
typed into it), shows what the bot and the learner are doing, and has buttons for the setup
commands. Served by the learner on the same port; the page and its calls only answer requests
from this computer.
"""

import collections
import json
import os
import platform
import re
import subprocess
import threading
import time
import webbrowser
from pathlib import Path

from . import targets as targets_mod

ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")
STATIC = Path(__file__).resolve().parent / "static"


class Zenith:
    """ZenithProxy run through its official launcher, kept running while wanted."""

    def __init__(self, folder, log=print, env=None):
        self.folder = Path(folder) if folder else None
        self.log = log
        self.env = env or {}
        self.proc = None
        self.want = False
        self.lines = collections.deque(maxlen=2000)
        self.seq = 0
        self.lock = threading.Lock()
        self.started_at = None
        self.restarts = 0
        threading.Thread(target=self._keeper, name="zenith-keeper", daemon=True).start()

    def available(self):
        return self.folder is not None and self._command() is not None

    def _command(self):
        f = self.folder
        if f is None:
            return None
        if platform.system() == "Windows":
            if (f / "launch.bat").exists():
                return ["cmd", "/c", "launch.bat", "--unattended"]
            if (f / "launch.exe").exists():
                return [str(f / "launch.exe"), "--unattended"]
        else:
            for name in ("launch", "launch.sh"):
                if (f / name).exists():
                    return [str(f / name), "--unattended"]
        return None

    def running(self):
        return self.proc is not None and self.proc.poll() is None

    def _add(self, text):
        with self.lock:
            for line in text.splitlines() or [""]:
                self.seq += 1
                self.lines.append((self.seq, ANSI.sub("", line)))

    def since(self, after):
        with self.lock:
            return [{"n": n, "t": t} for n, t in self.lines if n > after]

    def start(self):
        self.want = True
        return self._spawn()

    def _spawn(self):
        if self.running():
            return "already running"
        cmd = self._command()
        if cmd is None:
            return "ZenithProxy is not installed in " + str(self.folder)
        env = dict(os.environ)
        env.update(self.env)
        flags = 0
        if platform.system() == "Windows":
            flags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
        self.proc = subprocess.Popen(cmd, cwd=str(self.folder), env=env, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=0,
                                     creationflags=flags)
        self.started_at = time.time()
        self._add("--- ZenithProxy starting ---")
        threading.Thread(target=self._pump, args=(self.proc,), name="zenith-output", daemon=True).start()
        return "starting"

    def _pump(self, proc):
        buf = b""
        while True:
            chunk = proc.stdout.read(4096)  # unbuffered: returns whatever has arrived
            if not chunk:
                break
            buf += chunk
            *done, buf = buf.split(b"\n")
            for raw in done:
                self._add(raw.decode("utf-8", errors="replace").rstrip("\r"))
        if buf:
            self._add(buf.decode("utf-8", errors="replace"))
        self._add(f"--- ZenithProxy stopped (exit {proc.wait()}) ---")

    def send(self, command):
        command = command.strip()
        if not command:
            return "nothing to send"
        if not self.running():
            return "ZenithProxy is not running"
        self._add("> " + command)
        try:
            self.proc.stdin.write((command + "\n").encode())
            self.proc.stdin.flush()
        except OSError as e:
            return f"could not send: {e}"
        return "sent"

    def stop(self):
        self.want = False
        if not self.running():
            return "not running"
        self.send("shutdown")
        proc = self.proc

        def finish():
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self._kill(proc)

        threading.Thread(target=finish, daemon=True).start()
        return "stopping"

    def _kill(self, proc):
        if platform.system() == "Windows":
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(proc.pid)], capture_output=True)
        else:
            proc.kill()
        self._add("--- ZenithProxy killed ---")

    def _keeper(self):
        """Starts ZenithProxy again when it stops while wanted (a crash, or LootBot's self-update restart)."""
        while True:
            time.sleep(10)
            if self.want and not self.running() and self.proc is not None:
                self.restarts += 1
                self._add("--- restarting ZenithProxy ---")
                try:
                    self._spawn()
                except Exception as e:
                    self._add(f"--- restart failed: {e} ---")

    def shutdown(self):
        self.want = False
        if self.running():
            self.send("shutdown")
            try:
                self.proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self._kill(self.proc)


class Dashboard:
    def __init__(self, learner, zenith):
        self.learner = learner
        self.zenith = zenith

    @staticmethod
    def local(handler):
        return handler.client_address[0] in ("127.0.0.1", "::1", "::ffff:127.0.0.1")

    def page(self):
        return (STATIC / "dashboard.html").read_bytes()

    def state(self, after=0):
        db = self.learner.db
        now = time.time()
        day = now - 86400
        ep = db.one("SELECT COUNT(*) AS n, COALESCE(SUM(value), 0) AS v, COALESCE(SUM(deaths), 0) AS d "
                    "FROM episodes WHERE received_at >= ?", (day,))
        bots = []
        for b in db.all("SELECT name, version, last_seen, state FROM bots ORDER BY last_seen DESC"):
            st = json.loads(b["state"] or "{}")
            bots.append({"name": b["name"], "version": b["version"], "ago": round(now - (b["last_seen"] or 0)),
                         "x": st.get("x"), "z": st.get("z"), "dim": st.get("dim"), "health": st.get("health"),
                         "freeSlots": st.get("freeSlots")})
        open_targets = db.all(
            "SELECT id, x, z, dim, label, source, attempts, last_outcome, assigned_to, not_before, weight "
            "FROM targets WHERE status = 'open' ORDER BY (assigned_to IS NULL), weight DESC, id LIMIT 60")
        for t in open_targets:
            t["parked"] = max(0, round((t.pop("not_before") or 0) - now))
        episodes = []
        for e in db.all("SELECT bot, target_id, outcome, value, reward, seconds, raw, received_at FROM episodes "
                        "ORDER BY received_at DESC LIMIT 25"):
            raw = json.loads(e.pop("raw"))
            items = sorted((raw.get("items") or {}).items(),
                           key=lambda kv: -self.learner.values.item(kv[0]) * kv[1])[:4]
            e["items"] = ", ".join(f"{n} {k.replace('in:', '')}" for k, n in items)
            e["ago"] = round(now - e.pop("received_at"))
            episodes.append(e)
        events = db.all("SELECT bot, kind, text, at FROM events ORDER BY at DESC LIMIT 30")
        for ev in events:
            ev["ago"] = round(now - ev.pop("at"))
        tuner = self.learner.tuner
        best = tuner.params(tuner.incumbent()) or {}
        cfg = self.learner.config
        return {
            "zenith": {"installed": self.zenith.available(), "running": self.zenith.running(),
                       "uptime": round(now - self.zenith.started_at) if self.zenith.running() and self.zenith.started_at else 0,
                       "restarts": self.zenith.restarts, "lines": self.zenith.since(after)},
            "day": {"episodes": ep["n"], "points": round(ep["v"]), "deaths": ep["d"]},
            "targets": {"counts": targets_mod.counts(db), "open": open_targets},
            "bots": bots,
            "episodes": episodes,
            "events": events,
            "best": best,
            "sources": {"discord": bool(cfg.get("findsBot", {}).get("url")),
                        "gridfly": bool(cfg.get("gridfly", {}).get("url"))},
            "live": (self.learner.deployer.live() or {}).get("version"),
        }

    def view(self):
        """The freshest live view of any bot, with its age in seconds; {} when there is none."""
        views = list(self.learner.views.values())
        if not views:
            return {}
        v = max(views, key=lambda x: x.get("receivedAt", 0))
        return {**v, "age": round(time.time() - v.get("receivedAt", 0), 1)}

    def eyes(self, since=-1):
        """What the freshest bot sees: its pose, and the blocks around it when the client's copy is old."""
        live = [e for e in self.learner.eyes.values() if e.get("blocks") is not None]
        if not live:
            return {"waiting": True}
        e = max(live, key=lambda x: x.get("at", 0))
        out = {"v": e["v"], "pose": e.get("pose"), "age": round(time.time() - e.get("at", 0), 1)}
        if since != e["v"]:
            out["blocks"] = e["blocks"]
        return out

    def post(self, path, body):
        if path == "/ui/zenith/start":
            return {"msg": self.zenith.start()}
        if path == "/ui/zenith/stop":
            return {"msg": self.zenith.stop()}
        if path == "/ui/zenith/cmd":
            return {"msg": self.zenith.send(str(body.get("cmd") or ""))}
        if path == "/ui/finds/sync":
            if not self.learner.config.get("findsBot", {}).get("url"):
                return {"msg": "no finds bot set in config.json"}
            a, r, c, m = self.learner.findsbot.run()
            return {"msg": f"{a} new, {r} refreshed, {c} closed, {m} marked visited"}
        m = re.fullmatch(r"/ui/targets/(\d+)/(skip|reopen)", path)
        if m:
            tid, what = int(m.group(1)), m.group(2)
            with self.learner.db.tx() as c:
                if what == "skip":
                    c.execute("UPDATE targets SET status = 'skipped', assigned_to = NULL WHERE id = ?", (tid,))
                else:
                    c.execute("UPDATE targets SET status = 'open', not_before = 0 WHERE id = ?", (tid,))
            return {"msg": f"target {tid} {what}ped" if what == "skip" else f"target {tid} reopened"}
        if path == "/ui/targets/add":
            tid = targets_mod.add(self.learner.db, float(body["x"]), float(body["z"]), None,
                                  body.get("dim") or "overworld", body.get("label") or None, "manual")
            return {"msg": f"target {tid} added"}
        return None


def open_browser(url):
    try:
        webbrowser.open(url)
    except Exception:
        pass
