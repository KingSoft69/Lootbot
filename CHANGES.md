# Changes

Newest first. Each entry: what changed, the evidence for it, and what result would show it worked.

## 0.1.1

Live view for the local dashboard: about once a second the plugin sends a top-down map of the
blocks around the bot (read from just over its head down, so roofs don't hide the floor), its pose,
nearby players and what the loot job is doing (target, containers done and to do, loot taken).
'loot view off' turns it off. No change to how the bot loots.

## Dashboard

The learner can run a local dashboard (`dashboard --zenith <folder>`) that starts and stops
ZenithProxy, shows its console, has buttons for the setup steps, and lists targets and visits.

## Learner: targets from the Discord finds

The learner takes targets from the Discord finds bot (the one Revive's Finds module reads) every
5 minutes, drops finds that get closed on Discord, puts High and Moderate finds first, and marks a
find visited on Discord once a bot has been through it. Needs `findsBot.url` and `findsBot.key` in
the learner's config; restart the learner to pick it up.

## Learner: targets from GridFly

The learner takes targets from the GridFly server's finds (best unlooted spots with containers)
every 30 minutes and marks spots looted on GridFly once a bot has been through them. Needs
`gridfly.url` and `gridfly.token` in the learner's config; restart the learner to pick it up.

## 0.1.0

First version. Loots containers at targets from the learner (chests, barrels,
placed shulker boxes broken and picked up whole; trapped chests skipped), shulker boxes first then
the most valuable items, unloads at home, reports every target visit. Needs a saved kit ('loot kit')
before it starts, and never unloads kit items. Ends a target when a stranger is anywhere in range
or health is low, and waits to heal before the next one. The learner tunes ten settings (`learner/space.json`)
and runs new releases on a canary bot before everyone else.
