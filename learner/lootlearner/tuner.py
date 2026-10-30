"""The settings tuner: a hill climber with repeated trials.

Each param set is a full set of plugin settings, identified by a hash of its JSON. The incumbent is
the set with the best conservative score (mean reward less one standard error) among those with
enough episodes. New sets are single or double mutations of the incumbent. A new set that does
well on its first episodes is given more until it has enough to be judged, so one lucky base does
not crown it. Base-to-base noise is large, which is why nothing is judged on a single episode.
"""

import hashlib
import json
import math
import random
import time
from pathlib import Path

DEFAULT_SPACE = Path(__file__).resolve().parent.parent / "space.json"

# Outcomes that say nothing about the settings (the bot never got to use them).
NEUTRAL_OUTCOMES = {"wrong_dimension", "disconnected", "stopped", "travel_failed", "aborted_player"}


def load_space(path=None):
    with open(path or DEFAULT_SPACE, encoding="utf-8") as f:
        raw = json.load(f)
    return {k: v for k, v in raw.items() if not k.startswith("_")}


def params_id(params):
    canon = json.dumps(params, sort_keys=True, separators=(",", ":"))
    return hashlib.sha1(canon.encode()).hexdigest()[:10]


class Tuner:
    def __init__(self, db, space=None, min_episodes=4, explore=0.25, confirm_ratio=0.8, rng=None):
        self.db = db
        self.space = space or load_space()
        self.min_episodes = min_episodes
        self.explore = explore
        self.confirm_ratio = confirm_ratio
        self.rng = rng or random.Random()

    # -- param sets

    def defaults(self):
        return {k: v["default"] for k, v in self.space.items()}

    def _store(self, params, parent, origin):
        pid = params_id(params)
        with self.db.tx() as c:
            c.execute(
                "INSERT OR IGNORE INTO param_sets (id, params, parent, origin, created_at) VALUES (?, ?, ?, ?, ?)",
                (pid, json.dumps(params, sort_keys=True), parent, origin, time.time()),
            )
        return pid

    def params(self, pid):
        row = self.db.one("SELECT params FROM param_sets WHERE id = ?", (pid,))
        return json.loads(row["params"]) if row else None

    def mutate(self, params):
        out = dict(params)
        keys = list(self.space)
        for key in self.rng.sample(keys, k=1 if self.rng.random() < 0.7 else 2):
            choices = [c for c in self.space[key]["choices"] if c != out.get(key)]
            if choices:
                out[key] = self.rng.choice(choices)
        # Settings added to the space since the incumbent was made start at their default.
        for k, v in self.space.items():
            out.setdefault(k, v["default"])
        return {k: out[k] for k in self.space}

    # -- scoring

    def stats(self):
        """Per param set: episode count, mean reward, standard error, score."""
        rows = self.db.all(
            "SELECT params_id, reward FROM episodes WHERE params_id IS NOT NULL AND reward IS NOT NULL "
            "AND outcome NOT IN (%s)" % ",".join("?" * len(NEUTRAL_OUTCOMES)),
            tuple(NEUTRAL_OUTCOMES),
        )
        by = {}
        for r in rows:
            by.setdefault(r["params_id"], []).append(r["reward"])
        out = {}
        for pid, rewards in by.items():
            n = len(rewards)
            mean = sum(rewards) / n
            var = sum((x - mean) ** 2 for x in rewards) / (n - 1) if n > 1 else 0.0
            se = math.sqrt(var / n) if n > 1 else None
            out[pid] = {"n": n, "mean": mean, "se": se, "score": mean - se if se is not None else 0.0}
        return out

    def incumbent(self, stats=None):
        stats = stats if stats is not None else self.stats()
        judged = {p: s for p, s in stats.items() if s["n"] >= self.min_episodes}
        if not judged:
            return self._store(self.defaults(), None, "default")
        return max(judged, key=lambda p: judged[p]["score"])

    # -- the choice for the next episode

    def choose(self):
        """(params_id, params, why) for the next episode."""
        stats = self.stats()
        inc = self.incumbent(stats)
        inc_mean = stats.get(inc, {}).get("mean", 0.0)
        # A newcomer that looks at least nearly as good gets more episodes until it can be judged.
        hopefuls = [
            (p, s) for p, s in stats.items()
            if p != inc and 0 < s["n"] < self.min_episodes and s["mean"] >= self.confirm_ratio * inc_mean
        ]
        if hopefuls:
            p = max(hopefuls, key=lambda t: t[1]["mean"])[0]
            return p, self.params(p), "confirming a promising variant"
        if self.rng.random() < self.explore:
            base = self.params(inc) or self.defaults()
            new = self.mutate(base)
            pid = self._store(new, inc, "mutation")
            return pid, new, "trying a variant of the best settings"
        params = self.params(inc) or self.defaults()
        # Settings added to the space later start at their default.
        for k, v in self.space.items():
            params.setdefault(k, v["default"])
        return inc, params, "best settings so far"

    def leaderboard(self, limit=10):
        stats = self.stats()
        inc = self.incumbent(stats)
        rows = []
        for pid, s in sorted(stats.items(), key=lambda t: -t[1]["mean"])[:limit]:
            base = self.params(inc) or {}
            mine = self.params(pid) or {}
            diff = {k: v for k, v in mine.items() if base.get(k) != v}
            rows.append({"id": pid, "incumbent": pid == inc, **s, "diff": diff})
        return rows
