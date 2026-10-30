# LootBot

A ZenithProxy plugin (`plugin/`) that loots known 2b2t bases handed out by a learner service
(`learner/`), which tunes the plugin's settings from the results and rolls new plugin versions
out through a canary bot. This file is also the brief for the scheduled review: a session that
reads the learner's report and ships one improvement, with nobody watching.

## Layout

- `plugin/` ZenithProxy plugin, its own Gradle build (JDK 25 toolchain, `maven.2b2t.vc`).
  `LootJob` is the state machine (ask, travel, settle, loot, home, deposit), `Episode` what is
  reported, `Params` the tunable settings, `ContainerScan` finds containers from chunk block entities.
- `learner/` Python 3.11+, standard library only. `server.py` (HTTP + background loop), `tuner.py`
  (hill climber over `space.json`), `targets.py` (queue and outcome rules), `deploy.py` (releases,
  canary, rollback), `values.py` + `values.json` (item points, reward), `reports.py`, `gridfly.py`
  (targets from the GridFly server's finds over its operator websocket, looted marks back).
- `deploy/` systemd unit for the learner, `watchdog.py` (cron on the droplets: puts the previous jar
  back when a new one is bad or never loads).

Tests: `cd learner && python -m unittest discover -s tests`. The plugin cannot be built inside a
Claude session (the Maven hosts are not reachable from it); CI builds it on every push.

## Commits

Author every commit as `KingSoft <kingsoft@noreply.lootbot.dev>`, no Co-Authored-By or session
trailers, and no personal names or handles anywhere in the repository.

```
git -c user.name=KingSoft -c user.email=kingsoft@noreply.lootbot.dev commit -m "..."
```

## The review loop

Runs on a schedule. The learner pushes `reports/latest.md` and `reports/latest.json` to the
`reports` branch every few hours.

1. `git fetch origin reports main` and read `git show origin/reports:reports/latest.md` (and the
   JSON for exact numbers). Read `CHANGES.md` for what earlier runs changed and why.
2. Check the last release: the report's Releases section says whether it went live or was rolled
   back, and why. A rolled-back change is not tried again in the same form.
3. If a canary is still on trial, or there are fewer than 20 episodes since the last release went
   live, stop here: there is not enough to judge anything new against. Note that in the run's
   final message and end.
4. Pick the one change the evidence most supports. In order of preference:
   - a plugin fix for the most common failure reason or episode note (e.g. containers that are
     clicked but never open, shulker boxes broken but not picked up, travel that stalls);
   - a change to `learner/space.json` (add a choice next to the incumbent's value when the best
     settings sit at the edge of a range; drop choices that never win);
   - a change to how targets are chosen or outcomes are handled in `targets.py`;
   - item values in `values.json` only when the report shows loot being left or taken wrongly.
   One behavioural change per release, so the canary result says something about it.
5. Make it on a branch `auto/<yyyy-mm-dd>`, add a test when the learner changes, run the learner
   tests, bump `plugin_version` in `plugin/gradle.properties` (patch number) whenever the plugin
   changes, and add a `## <version>` entry at the top of `CHANGES.md`: what changed, the report
   numbers that motivated it, and what result would show it worked.
6. Push the branch and wait for CI on it (`gh api repos/{owner}/{repo}/actions/runs?branch=auto/...`,
   poll every minute, up to 30 minutes). If CI fails, fix it on the branch; after two failed fixes,
   abandon the branch and end with a note of why.
7. When CI passes: fast-forward `main` to the branch and push `main`. A new `plugin_version` on main
   is released by the Release workflow by itself (tag, jar, checksum; tags cannot be pushed from a
   Claude session, so never try). The learner tries it on the canary bot and promotes it or rolls
   it back by itself. A learner-only change takes effect when
   the learner is restarted on the droplet, so say so in `CHANGES.md` and the final message.
8. End with a short final message: the change, the evidence, the release.

### Never

- Remove or weaken the safety stops (`abortOnPlayer`, `abortHealth`, death handling), the canary,
  the rollback, or the watchdog's `.bak` restore.
- Make the bot attack players, place blocks other than what looting needs, or send anything to
  any address other than the learner.
- Touch the reports branch by hand, rewrite history on `main`, or change how releases are named.
- Ship more than one release per run.
