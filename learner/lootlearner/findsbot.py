"""Targets from the team's Discord finds (the #search-finds forum), through the finds bot that
Revive's Finds module reads.

The finds bot serves the open finds over HTTP with a shared key:
    GET  /finds                  {"finds": [{"id", "title", "priority", "x", "z", "url"}, ...]}  (overworld coords)
    POST /finds/<id>/visited     {"player": "..."}  posts "Visited by ..." in the thread and closes it
Every open find becomes a target (High and Moderate finds are visited first). A find that is no
longer open (someone marked it visited, or the thread was closed) is dropped from the queue. Once a
bot has been through a find, it is marked visited on Discord the same way the Finds module does.
"""

import json
import time
import urllib.error
import urllib.request

from . import targets as targets_mod

PREFIX = "discord:"
# Stands in for a score next to GridFly's: how much a find's priority pulls it forward.
WEIGHT = {"high": 60.0, "moderate": 20.0, "medium": 20.0, "low": 4.0}


class FindsBot:
    def __init__(self, url, key, timeout=30):
        u = url.strip().rstrip("/")
        if not u.startswith(("http://", "https://")):
            u = "http://" + u
        self.base = u
        self.key = key
        self.timeout = timeout

    def _call(self, method, path, body=None):
        req = urllib.request.Request(self.base + path, method=method,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={"X-Finds-Key": self.key, "Content-Type": "application/json",
                                              "User-Agent": "lootlearner"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                data = r.read()
                return json.loads(data) if data else {}
        except urllib.error.HTTPError as e:
            if e.code == 401:
                raise PermissionError("the finds bot refused the key") from e
            raise

    def finds(self):
        return self._call("GET", "/finds").get("finds") or []

    def visited(self, find_id, player):
        self._call("POST", f"/finds/{find_id}/visited", {"player": player})


def import_finds(db, finds, now=None):
    """Adds a target per open find, refreshes known ones, and closes targets whose find is gone.
    Returns (added, refreshed, closed)."""
    now = now or time.time()
    added = refreshed = 0
    open_keys = set()
    for f in finds:
        try:
            fid, x, z = str(f["id"]), int(f["x"]), int(f["z"])
        except (KeyError, TypeError, ValueError):
            continue
        key = PREFIX + fid
        open_keys.add(key)
        prio = str(f.get("priority") or "").strip().lower()
        label = (f"{f.get('priority') or ''} {f.get('title') or ''}".strip())[:200] or None
        weight = WEIGHT.get(prio, 10.0)
        existing = db.one("SELECT id FROM targets WHERE ext_key = ?", (key,))
        if existing:
            with db.tx() as c:
                c.execute("UPDATE targets SET label = ?, weight = ? WHERE id = ?", (label, weight, existing["id"]))
            refreshed += 1
            continue
        tid = targets_mod.add(db, x, z, None, "overworld", label, "discord:" + (prio or "unrated"))
        with db.tx() as c:
            # A spot already known from elsewhere keeps its record and takes the Discord link.
            c.execute("UPDATE targets SET ext_key = ?, ext_ids = ?, weight = MAX(COALESCE(weight, 0), ?) WHERE id = ?",
                      (key, json.dumps([fid]), weight, tid))
        added += 1
    # Finds closed on Discord (visited by someone) leave the queue, unless a bot is on one right now.
    closed = 0
    for t in db.all("SELECT id FROM targets WHERE status = 'open' AND ext_key LIKE ? AND assigned_to IS NULL",
                    (PREFIX + "%",)):
        row = db.one("SELECT ext_key FROM targets WHERE id = ?", (t["id"],))
        if row["ext_key"] not in open_keys:
            with db.tx() as c:
                c.execute("UPDATE targets SET status = 'closed', last_outcome = 'closed_on_discord', last_visit = ? "
                          "WHERE id = ?", (now, t["id"]))
            closed += 1
    return added, refreshed, closed


class Sync:
    def __init__(self, db, config, log=print, connect=None):
        self.db = db
        self.config = config  # the "findsBot" section
        self.log = log
        self.connect = connect or (lambda: FindsBot(config["url"], config.get("key", "")))
        self.last = 0.0

    def due(self, now):
        return bool(self.config.get("url")) and now - self.last >= float(self.config.get("syncMinutes", 5)) * 60

    def run(self, now=None):
        self.last = now or time.time()
        bot = self.connect()
        marked = 0
        if self.config.get("markVisited", True):
            for t in self.db.all("SELECT id, ext_ids, ext_pending FROM targets WHERE ext_pending IS NOT NULL AND ext_key LIKE ?",
                                 (PREFIX + "%",)):
                fid = json.loads(t["ext_ids"])[0]
                try:
                    bot.visited(fid, t["ext_pending"][:150])
                except urllib.error.HTTPError as e:
                    if e.code != 404:  # 404: already closed by someone else
                        raise
                with self.db.tx() as c:
                    c.execute("UPDATE targets SET ext_pending = NULL WHERE id = ?", (t["id"],))
                marked += 1
        finds = bot.finds()
        added, refreshed, closed = import_finds(self.db, finds, self.last)
        msg = f"Discord finds: {len(finds)} open, {added} new target(s), {closed} closed, {marked} marked visited"
        self.log(msg)
        if added or closed or marked:
            self.db.event(None, "discord", msg)
        return added, refreshed, closed, marked
