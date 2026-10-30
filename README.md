# LootBot

Bots that loot known 2b2t bases and get better at it on their own.

```
  ZenithProxy + LootBot plugin  ──  /api/next, /api/episode  ──►  learner (droplet)
        ▲   loots a target, reports what happened                │ tunes settings, picks targets,
        │                                                        │ canaries new jars, writes reports
        └──── new jar (canary first, rolled back if worse) ◄─────┤
                                                                 ▼
                         reports branch ──► scheduled review (Claude): one fix per run,
                                            CI, tag ──► GitHub release ──► learner
```

Three loops, fastest first:

1. **Settings** (every episode). The learner hands each target out with a set of settings and scores
   the result as loot points per hour. Settings that do better become the default; variants keep
   being tried. Ten settings are tuned (`learner/space.json`): search radius, how long to wait for
   chunks, container order, timeouts, retries, whether to break shulker boxes, and so on.
2. **Targets** (every episode). Bases that turn out empty, unreachable or guarded are parked or
   dropped; sources whose bases tend to hold loot are visited first.
3. **Code** (scheduled). A Claude session reads the report, makes one change for the most common
   failure, gets it through CI and tags a release. The learner runs it on one canary bot, then
   promotes it or rolls it back on the numbers. `CLAUDE.md` has the rules it works to.

## Learner (on a droplet)

```
git clone <this repo> /root/lootbot && cd /root/lootbot/learner
python3 -m lootlearner --data /root/lootbot/data tokens     # makes data/config.json, prints the tokens
```

In `data/config.json` set `github.repo` (KingSoft69/LootBot) and `github.token`: a fine-grained token
with Contents read and write on this repository only. It lets the learner download releases and
push the reports the review loop reads. Then run it under systemd:

```
cp /root/lootbot/deploy/lootlearner.service /etc/systemd/system/
systemctl enable --now lootlearner
```

Port 25700 should only be open to the droplets the bots run on (`ufw allow from <ip> to any port 25700`).

### Targets from the Discord finds

The learner reads the team's #search-finds posts through the same finds bot Revive's Finds module
uses, every 5 minutes. Every open find becomes a target; High and Moderate finds are visited ahead
of closer Low ones, and a find someone marks visited (or closes) on Discord leaves the queue. When a
bot has been through a find, the learner marks it visited on Discord ("Visited by LootBot (<bot>):
took ...") and the thread closes, as the Finds module's Visited button does. In `data/config.json`,
with the same address and key you put in Revive's Finds module:

```
"findsBot": {"url": "http://<finds bot>:8790", "key": "<shared key>", "syncMinutes": 5, "markVisited": true}
```

`python3 -m lootlearner --data /root/lootbot/data finds sync` runs a sync straight away. Find
coordinates are overworld, so bots take these targets in the overworld.

### Targets from GridFly's finds (optional)

The learner logs in to the GridFly server like the command app does and takes the best unlooted
spots from the finds (shulker boxes, chest and barrel groups, netherite blocks; spots with only signs,
pearls or gateways are skipped) every 30 minutes. When a bot has been through a spot, its finds are
marked looted on GridFly with a note of what was taken, so the map and the Discord posts show it.
In `data/config.json`:

```
"gridfly": {"url": "ws://<gridfly server>:25600/ui", "token": "<operator token>", "syncMinutes": 30,
            "limit": 300, "minScore": 0, "markLooted": true}
```

The operator token is the one `gridfly-server --print-tokens` shows (use `wss://` when the server is
behind TLS). `python3 -m lootlearner --data /root/lootbot/data gridfly sync` runs a sync straight away.
The learner keeps track of which kind of find (shulkers, chests, netherite) actually pays and visits
those first, and GridFly's own score for a spot counts too.

Other targets, by hand or from a CSV with `x,z` columns (and optional `y`, `dim`, `label`, `source`):

```
python3 -m lootlearner --data /root/lootbot/data targets add 120000 -45000 --label "stash" --source finds
python3 -m lootlearner --data /root/lootbot/data targets import finds.csv --source finds
python3 -m lootlearner --data /root/lootbot/data targets gridfly gridfly-copy.db   # GridFly's stashes
python3 -m lootlearner --data /root/lootbot/data report
```

## On your own computer, with the dashboard

`python3 -m lootlearner --data data dashboard --zenith <ZenithProxy launcher folder>` runs the learner
and a dashboard at http://127.0.0.1:25700/ that also starts and stops ZenithProxy, shows its console,
takes commands, and has buttons for the setup steps (log in, save kit, set home, start looting). It
lists the targets waiting, the visits so far and what was taken. The dashboard only answers the
computer it runs on.

## Plugin (each ZenithProxy)

Build with `cd plugin && ./gradlew build` (or take the jar from a release), drop
`LootBot-<version>.jar` into `plugins/`, restart the proxy, then:

```
loot learner http://<learner ip>:25700 <bot token>
loot kit                      # what the bot carries now is its kit: everything else is loot
loot home here                # loot goes into chests/barrels within 8 blocks of here
loot travel gridfly goto {x} {z}      # optional: GridFly/HighwayRunner on the same proxy does the trips
loot travel stop gridfly nav stop
loot on
loot start
loot status
```

Without a travel command it walks with ZenithProxy's pathfinder (`loot travel elytra <blocks>` flies
trips over that distance with ZenithProxy's elytra pathing). It ends a target and moves on when a
player who is not on the friends list comes into range or health drops to 8 (`loot safety ...`).

## Watchdog (cron on each droplet)

A new jar that fails to load cannot be talked back down by the learner, so `deploy/watchdog.py`
puts the previous jar back (the plugin keeps it as `.jar.bak`) and restarts the proxy through its
tmux session. Copy the example from the top of the script into `deploy/watchdog.json` and add:

```
*/10 * * * * /usr/bin/python3 /root/lootbot/deploy/watchdog.py >> /root/lootbot/watchdog.log 2>&1
```
