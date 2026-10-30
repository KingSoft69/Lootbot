"""The target queue: known bases, who is on which, and what each outcome does to a target."""

import csv
import math
import time

ASSIGN_STALE_S = 3 * 3600

# outcome -> (new status, park seconds, counts as an attempt)
RULES = {
    "looted": ("done", 0, True),
    "full": ("open", 0, False),            # left with loot still there: back after unloading
    "no_room": ("open", 0, False),
    "empty": ("empty", 0, True),
    "no_containers": ("open", 3600, True),  # chunks may not have loaded: once more, later
    "unreachable": ("open", 3600, True),
    "travel_failed": ("open", 1800, True),
    "aborted_player": ("open", 6 * 3600, False),
    "aborted_health": ("open", 12 * 3600, True),
    "died": ("open", 24 * 3600, True),
    "wrong_dimension": ("open", 0, False),
    "disconnected": ("open", 0, False),
    "stopped": ("open", 0, False),
}
# Attempts after which an open target is given up.
MAX_ATTEMPTS = {"looted_more": 6, "no_containers": 2, "unreachable": 3, "travel_failed": 3, "aborted_health": 2, "died": 2}


def add(db, x, z, y=None, dim="overworld", label=None, source="manual"):
    """Adds a target; an existing one at the same spot is left as it is. Returns its id."""
    with db.tx() as c:
        c.execute(
            "INSERT OR IGNORE INTO targets (x, y, z, dim, label, source, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (int(x), None if y in (None, "") else int(y), int(z), dim or "overworld", label or None, source or "manual", time.time()),
        )
        row = c.execute("SELECT id FROM targets WHERE x = ? AND z = ? AND dim = ?", (int(x), int(z), dim or "overworld")).fetchone()
    return row["id"]


def import_csv(db, path, source="import"):
    """CSV with a header naming at least x and z; y, dim, label and source columns are optional."""
    n = 0
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            row = {k.strip().lower(): (v or "").strip() for k, v in row.items() if k}
            if not row.get("x") or not row.get("z"):
                continue
            add(db, float(row["x"]), float(row["z"]), row.get("y") or None, row.get("dim") or "overworld",
                row.get("label") or None, row.get("source") or source)
            n += 1
    return n


def import_gridfly(db, path, source="gridfly"):
    """Stashes from a copy of a GridFly server's database (its stashes table)."""
    import sqlite3

    src = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        rows = src.execute("SELECT id, dim, x, y, z FROM stashes WHERE x IS NOT NULL AND z IS NOT NULL").fetchall()
    finally:
        src.close()
    dims = {"overworld": "overworld", "nether": "nether", "the_nether": "nether", "end": "end", "the_end": "end"}
    for sid, dim, x, y, z in rows:
        add(db, x, z, y, dims.get(str(dim or "overworld").lower(), "overworld"), f"stash {sid}", source)
    return len(rows)


def source_odds(db):
    """Per source, the chance a visit found loot (a Beta(1, 1) prior, so new sources start at even)."""
    rows = db.all(
        "SELECT t.source AS source, SUM(CASE WHEN e.value > 0 THEN 1 ELSE 0 END) AS hit, COUNT(*) AS n "
        "FROM episodes e JOIN targets t ON t.id = e.target_id "
        "WHERE e.outcome NOT IN ('wrong_dimension', 'disconnected', 'stopped') GROUP BY t.source"
    )
    return {r["source"]: (1 + (r["hit"] or 0)) / (2 + r["n"]) for r in rows}


def pick(db, bot, state, now=None):
    """The best open target for this bot: likely to hold loot and close by. Marks it assigned."""
    now = now or time.time()
    dim = (state or {}).get("dim", "overworld")
    bx, bz = float((state or {}).get("x", 0)), float((state or {}).get("z", 0))
    rows = db.all(
        "SELECT * FROM targets WHERE status = 'open' AND dim = ? AND not_before <= ? "
        "AND (assigned_to IS NULL OR assigned_to = ? OR assigned_at < ?)",
        (dim, now, bot, now - ASSIGN_STALE_S),
    )
    if not rows:
        return None
    odds = source_odds(db)

    def score(t):
        d = math.hypot(t["x"] - bx, t["z"] - bz)
        fresh = 1.15 if t["attempts"] == 0 else 1.0
        # GridFly's own score for the spot (netherite and shulkers count most), dampened.
        worth = 1.0 + math.log1p(max(0.0, t.get("weight") or 0.0)) / 4.0
        return odds.get(t["source"], 0.5) * fresh * worth / (1.0 + d / 3000.0)

    best = max(rows, key=score)
    with db.tx() as c:
        c.execute("UPDATE targets SET assigned_to = NULL, assigned_at = NULL WHERE assigned_to = ?", (bot,))
        c.execute("UPDATE targets SET assigned_to = ?, assigned_at = ? WHERE id = ?", (bot, now, best["id"]))
    return best


def record(db, target_id, outcome, value, more_left=False, now=None):
    """Applies an episode's outcome to its target. more_left: the bot stopped at its container limit."""
    now = now or time.time()
    t = db.one("SELECT * FROM targets WHERE id = ?", (target_id,))
    if not t:
        return None
    status, park, attempt = RULES.get(outcome, ("open", 3600, True))
    rule = outcome
    if outcome == "looted" and more_left:
        status, park, attempt, rule = "open", 0, True, "looted_more"
    attempts = t["attempts"] + (1 if attempt else 0)
    if status == "open" and rule in MAX_ATTEMPTS and attempts >= MAX_ATTEMPTS[rule]:
        status = {"no_containers": "empty", "looted_more": "done"}.get(rule, "failed")
    with db.tx() as c:
        c.execute(
            "UPDATE targets SET status = ?, attempts = ?, last_outcome = ?, last_visit = ?, not_before = ?, "
            "assigned_to = NULL, assigned_at = NULL, value_total = value_total + ? WHERE id = ?",
            (status, attempts, outcome, now, now + park, max(0.0, value or 0.0), target_id),
        )
    return status


def counts(db):
    return {r["status"]: r["n"] for r in db.all("SELECT status, COUNT(*) AS n FROM targets GROUP BY status")}
