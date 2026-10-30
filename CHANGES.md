# Changes

Newest first. Each entry: what changed, the evidence for it, and what result would show it worked.

## 0.1.0

First version. Loots containers at targets from the learner (chests, barrels,
placed shulker boxes broken and picked up whole; trapped chests skipped), shulker boxes first then
the most valuable items, unloads at home, reports every target visit. Needs a saved kit ('loot kit')
before it starts, and never unloads kit items. Ends a target when a stranger is anywhere in range
or health is low, and waits to heal before the next one. The learner tunes ten settings (`learner/space.json`)
and runs new releases on a canary bot before everyone else.
