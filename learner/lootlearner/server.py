"""The learner's HTTP server and its background loop.

Bots (bot token):
    POST /api/next      {bot, version, state}  -> work: a target and the settings to try, or a version to install
    POST /api/episode   one finished target visit
    POST /api/event     a short note for the log
    GET  /jar/<version> a plugin jar
Operator (operator token):
    GET  /api/status    the report as JSON
    GET  /api/report    the report as Markdown
    POST /api/targets   {"targets": [{x, z, y?, dim?, label?, source?}, ...]}
"""

import json
import secrets
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from . import gridfly, reports, targets
from .db import DB
from .deploy import Deployer
from .tuner import Tuner, load_space
from .values import Values

DEFAULT_CONFIG = {
    "host": "0.0.0.0",
    "port": 25700,
    "botToken": "",
    "operatorToken": "",
    "github": {"repo": "", "token": "", "pollMinutes": 30, "reportsBranch": "reports", "reportHours": 6},
    # The GridFly server's operator websocket, e.g. ws://1.2.3.4:25600/ui, and its operator token.
    "gridfly": {"url": "", "token": "", "syncMinutes": 30, "limit": 300, "minScore": 0, "markLooted": True},
    "canary": {"bots": [], "episodes": 8, "minRatio": 0.85, "silentMinutes": 120},
    "tuner": {"minEpisodes": 4, "explore": 0.25},
    "idleRetrySeconds": 120,
}


def load_config(data_dir):
    path = Path(data_dir) / "config.json"
    cfg = json.loads(json.dumps(DEFAULT_CONFIG))
    if path.exists():
        saved = json.loads(path.read_text(encoding="utf-8"))
        for k, v in saved.items():
            if isinstance(v, dict) and isinstance(cfg.get(k), dict):
                cfg[k].update(v)
            else:
                cfg[k] = v
    changed = False
    for key in ("botToken", "operatorToken"):
        if not cfg[key]:
            cfg[key] = secrets.token_urlsafe(24)
            changed = True
    if changed or not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    return cfg


class Learner:
    """Everything the handlers need, in one place (also what the tests drive directly)."""

    def __init__(self, data_dir, config=None, values=None, space=None, rng=None, log=print):
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.config = config or load_config(self.data_dir)
        self.log = log
        self.db = DB(self.data_dir / "learner.db")
        self.values = values or Values.load()
        tcfg = self.config.get("tuner", {})
        self.tuner = Tuner(self.db, space or load_space(), min_episodes=int(tcfg.get("minEpisodes", 4)),
                           explore=float(tcfg.get("explore", 0.25)), rng=rng)
        self.deployer = Deployer(self.db, self.data_dir / "jars", self.config, log=log)
        self.gridfly = gridfly.Sync(self.db, self.config.setdefault("gridfly", dict(DEFAULT_CONFIG["gridfly"])), log=log)

    # -- bot calls

    def next(self, body):
        bot = str(body.get("bot") or "unknown")
        version = body.get("version")
        state = body.get("state") or {}
        self.db.seen(bot, version, state)
        self.deployer.adopt_running(version)
        want, jar = self.deployer.desired(bot)
        # A bot that cannot update (turned off, or the last try failed) is given work on what it runs.
        can_update = state.get("autoUpdate", True) and state.get("updateFailed") != want
        if want and jar and want != version and can_update:
            return {"version": want, "jar": jar, "note": f"install {want}", "retrySeconds": 60}
        t = targets.pick(self.db, bot, state)
        if not t:
            return {"note": "no open targets", "retrySeconds": int(self.config.get("idleRetrySeconds", 120))}
        pid, params, why = self.tuner.choose()
        params = dict(params)
        params["wanted"] = self.values.wanted()
        self.db.event(bot, "assign", f"target {t['id']} ({t['x']}, {t['z']}) with settings {pid}: {why}")
        return {
            "target": {"id": t["id"], "x": t["x"], "y": t["y"], "z": t["z"], "dim": t["dim"], "label": t["label"]},
            "params": params,
            "paramsId": pid,
            "version": want,
            "jar": jar,
        }

    def episode(self, ep):
        value, reward = self.values.reward(ep)
        notes = ep.get("notes") or []
        more_left = any("container limit" in str(n) for n in notes)
        with self.db.tx() as c:
            c.execute(
                "INSERT INTO episodes (bot, version, target_id, params_id, outcome, seconds, travel_s, loot_s, deaths, value, reward, raw, received_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (ep.get("bot"), ep.get("version"), ep.get("targetId"), ep.get("paramsId") or None, ep.get("outcome"),
                 ep.get("seconds"), ep.get("travelSeconds"), ep.get("lootSeconds"), int(ep.get("deaths") or 0),
                 value, reward, json.dumps(ep), time.time()),
            )
        status = targets.record(self.db, ep.get("targetId"), ep.get("outcome"), value, more_left=more_left)
        if status in ("done", "empty") and ep.get("targetId") is not None:
            top = sorted((ep.get("items") or {}).items(), key=lambda kv: -self.values.item(kv[0]) * kv[1])[:5]
            took = ", ".join(f"{n} {k.replace('in:', '')}" for k, n in top)
            gridfly.queue_looted(self.db, ep["targetId"],
                                 f"LootBot ({ep.get('bot')}): " + (f"took {took}" if took else "nothing worth taking"))
        return {"ok": True, "value": round(value, 1), "reward": round(reward, 1), "target": status}

    # -- the background loop

    def tick(self, now=None):
        """Called every minute: releases, the canary, the report."""
        now = now or time.time()
        gh = self.config.get("github", {})
        if now - getattr(self, "_last_poll", 0) >= float(gh.get("pollMinutes", 30)) * 60:
            self._last_poll = now
            self.deployer.poll_github()
        self.deployer.evaluate(now)
        if self.gridfly.due(now):
            try:
                self.gridfly.run(now)
            except Exception as e:
                self.log(f"GridFly sync failed: {e}")
        if now - getattr(self, "_last_report", 0) >= float(gh.get("reportHours", 6)) * 3600:
            self._last_report = now
            self.write_report(publish=True)

    def report(self, hours=24):
        return reports.build(self.db, self.tuner, self.deployer, self.values, hours)

    def write_report(self, publish=False):
        r = self.report()
        md = reports.markdown(r)
        out = self.data_dir / "reports"
        out.mkdir(exist_ok=True)
        (out / "latest.md").write_text(md, encoding="utf-8")
        (out / "latest.json").write_text(json.dumps(r, indent=1, default=str), encoding="utf-8")
        gh = self.config.get("github", {})
        if publish and gh.get("repo") and gh.get("token"):
            try:
                reports.publish_github(gh["repo"], gh["token"], gh.get("reportsBranch", "reports"),
                                       {"reports/latest.md": md, "reports/latest.json": json.dumps(r, indent=1, default=str)},
                                       log=self.log)
            except Exception as e:
                self.log(f"report publish failed: {e}")
        return md


def make_handler(learner):
    cfg = learner.config

    class Handler(BaseHTTPRequestHandler):
        server_version = "lootlearner"

        def log_message(self, fmt, *args):
            pass  # the default writes a line per request

        def _auth(self, *tokens):
            got = self.headers.get("Authorization", "")
            return any(t and secrets.compare_digest(got, f"Bearer {t}") for t in tokens)

        def _send(self, code, body, ctype="application/json"):
            data = body if isinstance(body, bytes) else (body if isinstance(body, str) else json.dumps(body, default=str)).encode()
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _body(self):
            n = int(self.headers.get("Content-Length") or 0)
            if n > 5_000_000:
                raise ValueError("body too large")
            return json.loads(self.rfile.read(n) or b"{}")

        def do_POST(self):
            try:
                if self.path == "/api/next" and self._auth(cfg["botToken"]):
                    return self._send(200, learner.next(self._body()))
                if self.path == "/api/episode" and self._auth(cfg["botToken"]):
                    return self._send(200, learner.episode(self._body()))
                if self.path == "/api/event" and self._auth(cfg["botToken"]):
                    b = self._body()
                    learner.db.event(b.get("bot"), str(b.get("kind"))[:40], str(b.get("text"))[:500])
                    return self._send(200, {"ok": True})
                if self.path == "/api/targets" and self._auth(cfg["operatorToken"]):
                    ids = [targets.add(learner.db, t["x"], t["z"], t.get("y"), t.get("dim", "overworld"),
                                       t.get("label"), t.get("source", "api")) for t in self._body().get("targets", [])]
                    return self._send(200, {"ok": True, "ids": ids})
                return self._send(401 if self.path.startswith("/api/") else 404, {"error": "no"})
            except Exception as e:
                learner.log(f"{self.path}: {e}\n{traceback.format_exc()}")
                return self._send(500, {"error": str(e)})

        def do_GET(self):
            try:
                if self.path.startswith("/jar/") and self._auth(cfg["botToken"], cfg["operatorToken"]):
                    path = learner.deployer.jar_path(self.path[5:])
                    if not path or not path.exists():
                        return self._send(404, {"error": "no such jar"})
                    return self._send(200, path.read_bytes(), "application/java-archive")
                if self.path == "/api/status" and self._auth(cfg["operatorToken"]):
                    return self._send(200, learner.report())
                if self.path == "/api/report" and self._auth(cfg["operatorToken"]):
                    return self._send(200, reports.markdown(learner.report()), "text/markdown; charset=utf-8")
                if self.path == "/health":
                    return self._send(200, {"ok": True})
                return self._send(401 if self.path.startswith(("/api/", "/jar/")) else 404, {"error": "no"})
            except Exception as e:
                learner.log(f"{self.path}: {e}\n{traceback.format_exc()}")
                return self._send(500, {"error": str(e)})

    return Handler


def serve(data_dir):
    learner = Learner(data_dir)
    cfg = learner.config

    def loop():
        while True:
            try:
                learner.tick()
            except Exception as e:
                learner.log(f"background loop: {e}\n{traceback.format_exc()}")
            time.sleep(60)

    threading.Thread(target=loop, name="learner-loop", daemon=True).start()
    httpd = ThreadingHTTPServer((cfg["host"], int(cfg["port"])), make_handler(learner))
    learner.log(f"lootlearner on {cfg['host']}:{cfg['port']} (data in {learner.data_dir})")
    httpd.serve_forever()
