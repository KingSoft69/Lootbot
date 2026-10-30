"""Plugin releases: found on GitHub, tried on a canary bot, promoted or rolled back on the numbers.

Release states: canary (on the canary bots only), live (everyone else), old (replaced), bad (rolled
back; never handed out again). A release is judged once the canary bots have sent enough episodes
on it; it is rolled back straight away if the canary bots go quiet (a jar that does not load), die
more, or fail more than the live version did over the same period.
"""

import hashlib
import json
import time
import urllib.request
from pathlib import Path


def version_key(v):
    out = []
    for part in str(v).split("."):
        num = "".join(ch for ch in part if ch.isdigit())
        out.append(int(num) if num else 0)
    return tuple(out)


class Deployer:
    def __init__(self, db, jar_dir, config, log=print):
        self.db = db
        self.jar_dir = Path(jar_dir)
        self.jar_dir.mkdir(parents=True, exist_ok=True)
        self.config = config  # the "github" and "canary" sections of the learner config
        self.log = log

    # -- state

    def live(self):
        rows = self.db.all("SELECT * FROM releases WHERE status = 'live'")
        return max(rows, key=lambda r: version_key(r["version"])) if rows else None

    def canary(self):
        rows = self.db.all("SELECT * FROM releases WHERE status = 'canary'")
        return max(rows, key=lambda r: version_key(r["version"])) if rows else None

    def _set(self, version, status, note=""):
        with self.db.tx() as c:
            c.execute("UPDATE releases SET status = ?, changed_at = ?, note = ? WHERE version = ?",
                      (status, time.time(), note, version))
        self.db.event(None, "release", f"{version}: {status}" + (f" ({note})" if note else ""))
        self.log(f"release {version}: {status} {note}")

    def canary_bots(self, now=None):
        now = now or time.time()
        named = self.config.get("canary", {}).get("bots") or []
        if named:
            return list(named)
        recent = self.db.all("SELECT name FROM bots WHERE last_seen > ? ORDER BY name", (now - 3600,))
        return [recent[0]["name"]] if recent else []

    def desired(self, bot):
        """(version, jar dict) this bot should run, or (None, None) when nothing is known."""
        can = self.canary()
        if can and bot in self.canary_bots():
            return can["version"], self._jar(can)
        lv = self.live()
        if lv:
            return lv["version"], self._jar(lv)
        return None, None

    def _jar(self, row):
        if not row.get("jar"):
            return None
        return {"url": "/jar/" + row["version"], "sha256": row["sha256"]}

    def jar_path(self, version):
        row = self.db.one("SELECT jar FROM releases WHERE version = ? AND status != 'bad'", (version,))
        return Path(row["jar"]) if row and row["jar"] else None

    # -- finding releases

    def adopt_running(self, version):
        """A version a bot already runs, with no release record yet (the first install): it is live."""
        if not version or self.db.one("SELECT 1 FROM releases WHERE version = ?", (version,)):
            return
        if self.live():
            return
        now = time.time()
        with self.db.tx() as c:
            c.execute("INSERT OR IGNORE INTO releases (version, jar, sha256, status, found_at, changed_at, note) "
                      "VALUES (?, NULL, NULL, 'live', ?, ?, 'running when first seen')", (version, now, now))

    def add_release(self, version, jar_bytes):
        """Stores a jar and makes it the canary (or live, when nothing is live yet)."""
        if self.db.one("SELECT 1 FROM releases WHERE version = ? AND jar IS NOT NULL", (version,)):
            return False
        path = self.jar_dir / f"LootBot-{version}.jar"
        path.write_bytes(jar_bytes)
        sha = hashlib.sha256(jar_bytes).hexdigest()
        lv = self.live()
        status = "canary" if lv and version_key(version) > version_key(lv["version"]) else ("live" if not lv else "old")
        if lv and lv["version"] == version:
            status = "live"
        now = time.time()
        with self.db.tx() as c:
            c.execute(
                "INSERT INTO releases (version, jar, sha256, status, found_at, changed_at, note) VALUES (?, ?, ?, ?, ?, ?, '') "
                "ON CONFLICT(version) DO UPDATE SET jar = excluded.jar, sha256 = excluded.sha256",
                (version, str(path), sha, status, now, now),
            )
            if status == "canary":
                # Only the newest canary runs: an older one still on trial is retired unjudged.
                c.execute("UPDATE releases SET status = 'old', note = 'superseded on trial' "
                          "WHERE status = 'canary' AND version != ?", (version,))
        self.db.event(None, "release", f"{version} found: {status}")
        self.log(f"release {version} stored as {status}")
        return True

    def poll_github(self):
        repo = self.config.get("github", {}).get("repo")
        if not repo:
            return 0
        token = self.config.get("github", {}).get("token") or ""
        try:
            releases = self._get_json(f"https://api.github.com/repos/{repo}/releases?per_page=10", token)
        except Exception as e:  # network trouble: next poll
            self.log(f"GitHub poll failed: {e}")
            return 0
        added = 0
        for rel in releases:
            if rel.get("draft") or rel.get("prerelease"):
                continue
            tag = rel.get("tag_name", "")
            version = tag[1:] if tag.startswith("v") else tag
            if self.db.one("SELECT 1 FROM releases WHERE version = ? AND (jar IS NOT NULL OR status = 'bad')", (version,)):
                continue
            jar = next((a for a in rel.get("assets", []) if a["name"] == f"LootBot-{version}.jar"), None)
            sha = next((a for a in rel.get("assets", []) if a["name"] == f"LootBot-{version}.jar.sha256"), None)
            if not jar:
                continue
            try:
                data = self._get_bytes(jar["browser_download_url"], token)
                if sha:
                    want = self._get_bytes(sha["browser_download_url"], token).decode().split()[0].strip().lower()
                    if hashlib.sha256(data).hexdigest() != want:
                        self.log(f"release {version}: SHA-256 mismatch, skipped")
                        continue
            except Exception as e:
                self.log(f"release {version}: download failed: {e}")
                continue
            if self.add_release(version, data):
                added += 1
        return added

    @staticmethod
    def _request(url, token, accept):
        req = urllib.request.Request(url, headers={"Accept": accept, "User-Agent": "lootlearner"})
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        return req

    def _get_json(self, url, token):
        with urllib.request.urlopen(self._request(url, token, "application/vnd.github+json"), timeout=30) as r:
            return json.loads(r.read())

    def _get_bytes(self, url, token):
        with urllib.request.urlopen(self._request(url, token, "application/octet-stream"), timeout=120) as r:
            return r.read()

    # -- judging the canary

    def _window_stats(self, version, since, bots=None):
        sql = "SELECT outcome, reward, deaths, bot FROM episodes WHERE version = ? AND received_at >= ?"
        rows = self.db.all(sql, (version, since))
        if bots is not None:
            rows = [r for r in rows if r["bot"] in bots]
        counted = [r for r in rows if r["outcome"] not in ("disconnected", "stopped", "wrong_dimension")]
        n = len(counted)
        if n == 0:
            return {"n": 0, "mean": 0.0, "deaths": 0.0, "fail": 0.0}
        fail = sum(1 for r in counted if r["outcome"] in ("unreachable", "travel_failed", "no_containers")) / n
        return {
            "n": n,
            "mean": sum(r["reward"] or 0 for r in counted) / n,
            "deaths": sum(r["deaths"] or 0 for r in counted) / n,
            "fail": fail,
        }

    def evaluate(self, now=None):
        """Promotes or rolls back the canary when there is enough to go on. Returns what it did."""
        now = now or time.time()
        can = self.canary()
        if not can:
            return None
        cfg = self.config.get("canary", {})
        need = int(cfg.get("episodes", 8))
        silent = float(cfg.get("silentMinutes", 120)) * 60
        min_ratio = float(cfg.get("minRatio", 0.85))
        bots = self.canary_bots(now)
        since = can["changed_at"]
        mine = self._window_stats(can["version"], since, set(bots))
        lv = self.live()
        # A canary bot that has not checked in on the new version for a long time: the jar is broken.
        on_new = self.db.all("SELECT name, last_seen FROM bots WHERE version = ?", (can["version"],))
        if now - since > silent and not on_new:
            self._set(can["version"], "bad", "no canary bot came back on it")
            return "rolled back (silent)"
        if mine["n"] < need:
            return None
        base = self._window_stats(lv["version"], since - 7 * 86400) if lv else {"n": 0}
        if base["n"] >= need:
            if mine["deaths"] > base["deaths"] + 0.05:
                self._set(can["version"], "bad", f"deaths per episode {mine['deaths']:.2f} vs {base['deaths']:.2f}")
                return "rolled back (deaths)"
            if mine["fail"] > base["fail"] + 0.15:
                self._set(can["version"], "bad", f"failure rate {mine['fail']:.0%} vs {base['fail']:.0%}")
                return "rolled back (failures)"
            if base["mean"] > 0 and mine["mean"] < min_ratio * base["mean"]:
                self._set(can["version"], "bad", f"reward {mine['mean']:.0f}/h vs {base['mean']:.0f}/h")
                return "rolled back (reward)"
        if lv:
            self._set(lv["version"], "old", f"replaced by {can['version']}")
        self._set(can["version"], "live", f"{mine['n']} canary episodes, reward {mine['mean']:.0f}/h"
                  + (f" vs {base['mean']:.0f}/h" if base.get("n") else ""))
        return "promoted"
