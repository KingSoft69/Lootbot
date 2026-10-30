"""The report the review loop reads: what happened, what keeps going wrong, what the tuner thinks.

Written as Markdown (for reading) and JSON (for code), kept under data/reports, and pushed to the
repository's reports branch when a GitHub token is set, which is where the scheduled review picks
it up.
"""

import base64
import collections
import json
import time
import urllib.error
import urllib.request

from . import targets as targets_mod


def _since(db, seconds):
    return db.all("SELECT * FROM episodes WHERE received_at >= ? ORDER BY received_at", (time.time() - seconds,))


def build(db, tuner, deployer, values, hours=24):
    now = time.time()
    recent = _since(db, hours * 3600)
    every = db.one("SELECT COUNT(*) AS n, COALESCE(SUM(value), 0) AS v FROM episodes")
    outcomes = collections.Counter(e["outcome"] for e in recent)
    by_version = collections.defaultdict(list)
    for e in recent:
        by_version[e["version"]].append(e)
    failures = collections.Counter()
    notes = collections.Counter()
    items = collections.Counter()
    loot_s, opened = 0.0, 0
    for e in recent:
        raw = json.loads(e["raw"])
        for f in raw.get("failures", []):
            failures[f.get("reason", "?")] += 1
        for n in raw.get("notes", []):
            notes[n] += 1
        for k, v in (raw.get("items") or {}).items():
            items[k] += int(v)
        loot_s += float(raw.get("lootSeconds") or 0)
        opened += int(raw.get("containersOpened") or 0)
    bots = db.all("SELECT name, version, last_seen FROM bots ORDER BY name")
    events = db.all("SELECT bot, kind, text, at FROM events WHERE at >= ? ORDER BY at DESC LIMIT 40", (now - hours * 3600,))
    releases = db.all("SELECT version, status, note, changed_at FROM releases ORDER BY changed_at DESC LIMIT 8")
    return {
        "generatedAt": now,
        "hours": hours,
        "episodes": len(recent),
        "episodesAllTime": every["n"],
        "valueAllTime": every["v"],
        "value": sum(e["value"] or 0 for e in recent),
        "deaths": sum(e["deaths"] or 0 for e in recent),
        "outcomes": dict(outcomes),
        "versions": {
            v: {
                "episodes": len(es),
                "meanReward": sum(e["reward"] or 0 for e in es) / len(es),
                "value": sum(e["value"] or 0 for e in es),
            }
            for v, es in by_version.items()
        },
        "secondsPerContainer": (loot_s / opened) if opened else None,
        "failureReasons": failures.most_common(15),
        "notes": notes.most_common(10),
        "topItems": [(k, n, round(values.item(k) * n, 1)) for k, n in items.most_common(15)],
        "targets": targets_mod.counts(db),
        "leaderboard": tuner.leaderboard(8),
        "space": tuner.space,
        "bots": [{**b, "minutesAgo": round((now - (b["last_seen"] or 0)) / 60)} for b in bots],
        "live": (deployer.live() or {}).get("version"),
        "canary": (deployer.canary() or {}).get("version"),
        "releases": releases,
        "events": events,
    }


def markdown(r):
    t = time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime(r["generatedAt"]))
    out = [f"# LootBot report, {t}", ""]
    out.append(f"Last {r['hours']} h: {r['episodes']} episodes, {r['value']:.0f} points looted, {r['deaths']} deaths. "
               f"All time: {r['episodesAllTime']} episodes, {r['valueAllTime']:.0f} points.")
    out.append(f"Live version {r['live'] or '-'}, canary {r['canary'] or 'none'}.")
    if r["secondsPerContainer"]:
        out.append(f"Loot time per container opened: {r['secondsPerContainer']:.1f} s.")
    out += ["", "## Outcomes", ""]
    out += [f"- {k}: {v}" for k, v in sorted(r["outcomes"].items(), key=lambda t: -t[1])] or ["- none"]
    out += ["", "## By version", "", "| version | episodes | mean reward /h | points |", "|---|---|---|---|"]
    for v, s in sorted(r["versions"].items()):
        out.append(f"| {v} | {s['episodes']} | {s['meanReward']:.0f} | {s['value']:.0f} |")
    out += ["", "## Why containers failed", ""]
    out += [f"- {n} x {reason}" for reason, n in r["failureReasons"]] or ["- none"]
    if r["notes"]:
        out += ["", "## Episode notes", ""]
        out += [f"- {n} x {note}" for note, n in r["notes"]]
    out += ["", "## Settings leaderboard", "", "| set | episodes | mean /h | score | differs from best |", "|---|---|---|---|---|"]
    for row in r["leaderboard"]:
        diff = ", ".join(f"{k}={v}" for k, v in row["diff"].items()) or ("(best)" if row["incumbent"] else "-")
        out.append(f"| {row['id']}{' *' if row['incumbent'] else ''} | {row['n']} | {row['mean']:.0f} | {row['score']:.0f} | {diff} |")
    out += ["", "## Top loot", ""]
    out += [f"- {n} {k} ({pts} pts)" for k, n, pts in r["topItems"]] or ["- none"]
    out += ["", "## Targets", ""]
    out += [f"- {k}: {v}" for k, v in sorted(r["targets"].items())] or ["- none"]
    out += ["", "## Bots", ""]
    out += [f"- {b['name']}: {b['version']}, seen {b['minutesAgo']} min ago" for b in r["bots"]] or ["- none"]
    out += ["", "## Releases", ""]
    out += [f"- {x['version']}: {x['status']} {x['note'] or ''}".rstrip() for x in r["releases"]] or ["- none"]
    if r["events"]:
        out += ["", "## Recent events", ""]
        for e in r["events"][:20]:
            when = time.strftime("%H:%M", time.gmtime(e["at"]))
            out.append(f"- {when} {e['bot'] or 'learner'} {e['kind']}: {e['text']}")
    return "\n".join(out) + "\n"


def publish_github(repo, token, branch, files, log=print):
    """Writes files ({path: text}) to a branch of the repository, creating the branch from the
    default branch when it does not exist yet."""
    api = f"https://api.github.com/repos/{repo}"

    def call(method, url, body=None):
        req = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode(),
                                     headers={"Accept": "application/vnd.github+json", "User-Agent": "lootlearner",
                                              "Authorization": f"Bearer {token}"})
        with urllib.request.urlopen(req, timeout=30) as r:
            data = r.read()
            return json.loads(data) if data else {}

    try:
        call("GET", f"{api}/git/ref/heads/{branch}")
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        default = call("GET", api)["default_branch"]
        sha = call("GET", f"{api}/git/ref/heads/{default}")["object"]["sha"]
        call("POST", f"{api}/git/refs", {"ref": f"refs/heads/{branch}", "sha": sha})
        log(f"created branch {branch}")
    for path, text in files.items():
        existing = None
        try:
            existing = call("GET", f"{api}/contents/{path}?ref={branch}").get("sha")
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
        body = {"message": f"Report {time.strftime('%Y-%m-%d %H:%M', time.gmtime())}", "branch": branch,
                "content": base64.b64encode(text.encode()).decode(),
                "committer": {"name": "LootBot Learner", "email": "learner@noreply.lootbot.dev"},
                "author": {"name": "LootBot Learner", "email": "learner@noreply.lootbot.dev"}}
        if existing:
            body["sha"] = existing
        call("PUT", f"{api}/contents/{path}", body)
