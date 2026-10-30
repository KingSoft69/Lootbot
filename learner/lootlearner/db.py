"""SQLite storage. One file, one connection per thread, every write in a transaction."""

import json
import sqlite3
import threading
import time

SCHEMA = """
CREATE TABLE IF NOT EXISTS targets (
    id INTEGER PRIMARY KEY,
    x INTEGER NOT NULL, y INTEGER, z INTEGER NOT NULL,
    dim TEXT NOT NULL DEFAULT 'overworld',
    label TEXT, source TEXT NOT NULL DEFAULT 'manual',
    status TEXT NOT NULL DEFAULT 'open',      -- open | done | empty | failed
    attempts INTEGER NOT NULL DEFAULT 0,
    last_outcome TEXT, last_visit REAL,
    not_before REAL NOT NULL DEFAULT 0,       -- parked until this time
    assigned_to TEXT, assigned_at REAL,
    value_total REAL NOT NULL DEFAULT 0,
    created_at REAL NOT NULL,
    UNIQUE (x, z, dim)
);
CREATE TABLE IF NOT EXISTS param_sets (
    id TEXT PRIMARY KEY,
    params TEXT NOT NULL,
    parent TEXT,
    origin TEXT NOT NULL,                     -- default | mutation | manual
    created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS episodes (
    id INTEGER PRIMARY KEY,
    bot TEXT NOT NULL, version TEXT,
    target_id INTEGER, params_id TEXT,
    outcome TEXT, seconds REAL, travel_s REAL, loot_s REAL,
    deaths INTEGER, value REAL, reward REAL,
    raw TEXT NOT NULL,
    received_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS episodes_params ON episodes (params_id);
CREATE INDEX IF NOT EXISTS episodes_version ON episodes (version, received_at);
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY, bot TEXT, kind TEXT, text TEXT, at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS bots (
    name TEXT PRIMARY KEY, version TEXT, last_seen REAL, state TEXT
);
CREATE TABLE IF NOT EXISTS releases (
    version TEXT PRIMARY KEY,
    jar TEXT, sha256 TEXT,
    status TEXT NOT NULL,                     -- canary | live | old | bad
    found_at REAL NOT NULL, changed_at REAL NOT NULL, note TEXT
);
"""


class DB:
    def __init__(self, path):
        self.path = str(path)
        self._local = threading.local()
        self.lock = threading.RLock()
        with self.tx() as c:
            c.executescript(SCHEMA)
            # Columns added after the first version.
            have = {r[1] for r in c.execute("PRAGMA table_info(targets)").fetchall()}
            for col, decl in (("ext_key", "TEXT"), ("ext_ids", "TEXT"), ("ext_pending", "TEXT"), ("weight", "REAL")):
                if col not in have:
                    c.execute(f"ALTER TABLE targets ADD COLUMN {col} {decl}")
            c.execute("CREATE INDEX IF NOT EXISTS targets_ext ON targets (ext_key)")

    def conn(self):
        c = getattr(self._local, "conn", None)
        if c is None:
            c = sqlite3.connect(self.path, timeout=30)
            c.row_factory = sqlite3.Row
            c.execute("PRAGMA journal_mode=WAL")
            self._local.conn = c
        return c

    def close(self):
        c = getattr(self._local, "conn", None)
        if c is not None:
            c.close()
            self._local.conn = None

    def tx(self):
        db = self

        class _Tx:
            def __enter__(self):
                db.lock.acquire()
                self.c = db.conn()
                return self.c

            def __exit__(self, kind, value, tb):
                try:
                    if kind is None:
                        self.c.commit()
                    else:
                        self.c.rollback()
                finally:
                    db.lock.release()
                return False

        return _Tx()

    def all(self, sql, args=()):
        return [dict(r) for r in self.conn().execute(sql, args).fetchall()]

    def one(self, sql, args=()):
        r = self.conn().execute(sql, args).fetchone()
        return dict(r) if r else None

    # -- small helpers used everywhere

    def event(self, bot, kind, text):
        with self.tx() as c:
            c.execute("INSERT INTO events (bot, kind, text, at) VALUES (?, ?, ?, ?)", (bot, kind, text, time.time()))

    def seen(self, bot, version, state):
        with self.tx() as c:
            c.execute(
                "INSERT INTO bots (name, version, last_seen, state) VALUES (?, ?, ?, ?) "
                "ON CONFLICT(name) DO UPDATE SET version=excluded.version, last_seen=excluded.last_seen, state=excluded.state",
                (bot, version, time.time(), json.dumps(state or {})),
            )
