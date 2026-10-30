package dev.lootbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One target visit: from leaving for the target until the job is ready for the next one. This is
 * what the learner scores; the field names are the learner's (learner/lootlearner/db.py).
 */
final class Episode {

    final LearnerClient.Target target;
    final Params params;
    final String paramsId;
    final long startedAt = System.currentTimeMillis();
    private final Map<String, Integer> before = Items.counts();

    String outcome;
    int ticks;
    int travelTicks;
    int deaths;
    int containersFound;
    int containersTried;
    int containersOpened;
    int containersEmpty;
    int containersFailed;
    int doubleChests;
    int shulkersBroken;
    int stacksTaken;
    final List<JsonObject> failures = new ArrayList<>();
    final List<String> notes = new ArrayList<>();

    Episode(LearnerClient.Target target, Params params, String paramsId) {
        this.target = target;
        this.params = params;
        this.paramsId = paramsId;
    }

    /** What the inventory gained since the episode began (shulker contents as "in:<id>"). */
    Map<String, Integer> lootSoFar() {
        return Items.gained(before, Items.counts());
    }

    String lootSummary() {
        Map<String, Integer> loot = lootSoFar();
        return loot.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(6)
                .map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));
    }

    JsonObject toJson(String bot, String version) {
        JsonObject o = new JsonObject();
        o.addProperty("bot", bot);
        o.addProperty("version", version);
        o.addProperty("targetId", target.id);
        o.addProperty("paramsId", paramsId);
        JsonElement p = LearnerClient.GSON.toJsonTree(params);
        o.add("params", p);
        o.addProperty("startedAt", startedAt);
        o.addProperty("seconds", (System.currentTimeMillis() - startedAt) / 1000.0);
        o.addProperty("travelSeconds", travelTicks / 20.0);
        o.addProperty("lootSeconds", Math.max(0, ticks - travelTicks) / 20.0);
        o.addProperty("outcome", outcome);
        o.addProperty("deaths", deaths);
        o.addProperty("containersFound", containersFound);
        o.addProperty("containersTried", containersTried);
        o.addProperty("containersOpened", containersOpened);
        o.addProperty("containersEmpty", containersEmpty);
        o.addProperty("containersFailed", containersFailed);
        o.addProperty("doubleChests", doubleChests);
        o.addProperty("shulkersBroken", shulkersBroken);
        o.addProperty("stacksTaken", stacksTaken);
        JsonObject items = new JsonObject();
        for (var e : lootSoFar().entrySet()) {
            items.addProperty(e.getKey(), e.getValue());
        }
        o.add("items", items);
        JsonArray f = new JsonArray();
        failures.forEach(f::add);
        o.add("failures", f);
        JsonArray n = new JsonArray();
        notes.forEach(n::add);
        o.add("notes", n);
        o.addProperty("x", LootJob.px());
        o.addProperty("y", LootJob.py());
        o.addProperty("z", LootJob.pz());
        o.addProperty("dim", LootJob.dimensionName());
        o.addProperty("health", LootJob.health());
        return o;
    }
}
