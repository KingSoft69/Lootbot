"""Item values and the episode reward."""

import json
from pathlib import Path

DEFAULT_PATH = Path(__file__).resolve().parent.parent / "values.json"

# Travel counts toward an episode's time only up to this many seconds: how far away a base is
# depends on the target, not the settings being tuned.
TRAVEL_CAP_S = 600
MIN_TIME_S = 60


class Values:
    def __init__(self, table):
        self.table = {k: v for k, v in table.items() if not k.startswith("_")}
        self.default = float(self.table.pop("default", 0.0))
        self.death_penalty = float(table.get("_penalty", {}).get("death", 150))

    @classmethod
    def load(cls, path=None):
        with open(path or DEFAULT_PATH, encoding="utf-8") as f:
            return cls(json.load(f))

    def item(self, name):
        name = name.split(":", 1)[1] if name.startswith("in:") else name
        if name.startswith("minecraft:"):
            name = name[10:]
        return float(self.table.get(name, self.default))

    def items(self, counts):
        return sum(self.item(k) * max(0, int(v)) for k, v in (counts or {}).items())

    def wanted(self, limit=40):
        """Item ids worth taking, most valuable first (shulker boxes are always taken by the bot)."""
        ranked = sorted(
            (k for k in self.table if not k.endswith("shulker_box")),
            key=lambda k: -self.table[k],
        )
        return [k for k in ranked if self.table[k] > self.default][:limit]

    def reward(self, ep):
        """Points per hour of the episode, deaths taken off, with travel counted up to a cap."""
        value = self.items(ep.get("items"))
        value -= self.death_penalty * int(ep.get("deaths") or 0)
        loot_s = float(ep.get("lootSeconds") or 0)
        travel_s = min(float(ep.get("travelSeconds") or 0), TRAVEL_CAP_S)
        seconds = max(MIN_TIME_S, loot_s + travel_s)
        return value, value / seconds * 3600.0
