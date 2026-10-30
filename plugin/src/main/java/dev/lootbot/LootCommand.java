package dev.lootbot;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.CustomStringArgumentType.getString;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static dev.lootbot.LootBotPlugin.CONFIG_DATA;

public class LootCommand extends Command {

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("loot")
            .category(CommandCategory.MODULE)
            .description("""
                LootBot. Takes known bases from the LootBot learner, loots their containers, unloads at
                home, and reports every visit so the learner can tune the settings. Settings live in
                plugins/config/lootbot.json.
                """)
            .usageLines(
                "on/off",
                "learner <url> <token>",
                "start",
                "stop",
                "status",
                "kit",
                "home here",
                "home off",
                "home radius <blocks>",
                "travel <command with {x} and {z}>",
                "travel off",
                "travel stop <command>",
                "travel elytra <blocks, 0 for never>",
                "safety health <n>",
                "safety players on/off",
                "update on/off"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("loot")
            .then(argument("toggle", toggle()).executes(c -> {
                CONFIG_DATA.enabled = getToggle(c, "toggle");
                module().syncEnabledFromConfig();
                c.getSource().getEmbed().title("LootBot " + toggleStrCaps(CONFIG_DATA.enabled));
            }))
            .then(literal("learner")
                .then(argument("url", wordWithChars())
                    .then(argument("token", wordWithChars()).executes(c -> {
                        CONFIG_DATA.learner.url = getString(c, "url");
                        CONFIG_DATA.learner.token = getString(c, "token");
                        c.getSource().getEmbed().title("LootBot Learner Set").description(CONFIG_DATA.learner.url);
                    }))))
            .then(literal("start").executes(c -> {
                c.getSource().getEmbed().title("LootBot").description(job().start());
            }))
            .then(literal("stop").executes(c -> {
                c.getSource().getEmbed().title("LootBot").description(job().stop());
            }))
            .then(literal("status").executes(c -> {
                c.getSource().getEmbed().title("LootBot Status").description(job().statusLine()
                    + "\nHome: " + (CONFIG_DATA.home.set ? CONFIG_DATA.home.x + ", " + CONFIG_DATA.home.y + ", " + CONFIG_DATA.home.z : "not set")
                    + "\nTravel: " + (CONFIG_DATA.travel.command.isBlank() ? "pathfinder" : CONFIG_DATA.travel.command)
                    + "\nKit: " + (CONFIG_DATA.kit.isEmpty() ? "not saved ('loot kit')" : kitLine(CONFIG_DATA.kit)));
            }))
            .then(literal("kit").executes(c -> {
                if (job().busy()) {
                    c.getSource().getEmbed().title("LootBot").description("Not while looting or unloading");
                    return;
                }
                Map<String, Integer> kit = new HashMap<>(Items.counts());
                kit.keySet().removeIf(k -> k.startsWith("in:"));
                CONFIG_DATA.kit = kit;
                c.getSource().getEmbed().title("LootBot Kit Saved").description(kitLine(kit));
            }))
            .then(literal("home")
                .then(literal("here").executes(c -> {
                    CONFIG_DATA.home.set = true;
                    CONFIG_DATA.home.x = (int) Math.floor(LootJob.px());
                    CONFIG_DATA.home.y = (int) Math.floor(LootJob.py());
                    CONFIG_DATA.home.z = (int) Math.floor(LootJob.pz());
                    c.getSource().getEmbed().title("LootBot Home Set")
                        .description(CONFIG_DATA.home.x + ", " + CONFIG_DATA.home.y + ", " + CONFIG_DATA.home.z
                            + "; loot goes into containers within " + CONFIG_DATA.home.radius + " blocks");
                }))
                .then(literal("off").executes(c -> {
                    CONFIG_DATA.home.set = false;
                    c.getSource().getEmbed().title("LootBot Home Off");
                }))
                .then(literal("radius").then(argument("blocks", integer(2, 64)).executes(c -> {
                    CONFIG_DATA.home.radius = getInteger(c, "blocks");
                    c.getSource().getEmbed().title("LootBot Home Radius").description(String.valueOf(CONFIG_DATA.home.radius));
                }))))
            .then(literal("travel")
                .then(literal("off").executes(c -> {
                    CONFIG_DATA.travel.command = "";
                    c.getSource().getEmbed().title("LootBot Travel").description("ZenithProxy's pathfinder");
                }))
                .then(literal("stop").then(argument("command", greedyString()).executes(c -> {
                    CONFIG_DATA.travel.stopCommand = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "command");
                    c.getSource().getEmbed().title("LootBot Travel Stop").description(CONFIG_DATA.travel.stopCommand);
                })))
                .then(literal("elytra").then(argument("blocks", integer(0)).executes(c -> {
                    CONFIG_DATA.travel.elytraOver = getInteger(c, "blocks");
                    c.getSource().getEmbed().title("LootBot Travel Elytra")
                        .description(CONFIG_DATA.travel.elytraOver == 0 ? "never" : "over " + CONFIG_DATA.travel.elytraOver + " blocks");
                })))
                .then(argument("command", greedyString()).executes(c -> {
                    String cmd = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "command");
                    if (!cmd.contains("{x}") || !cmd.contains("{z}")) {
                        c.getSource().getEmbed().title("LootBot Travel").description("The command needs {x} and {z}, e.g. gridfly goto {x} {z}");
                        return;
                    }
                    CONFIG_DATA.travel.command = cmd;
                    c.getSource().getEmbed().title("LootBot Travel").description(cmd);
                })))
            .then(literal("safety")
                .then(literal("health").then(argument("n", integer(0, 19)).executes(c -> {
                    CONFIG_DATA.safety.abortHealth = getInteger(c, "n");
                    c.getSource().getEmbed().title("LootBot Safety").description("abort at health " + CONFIG_DATA.safety.abortHealth);
                })))
                .then(literal("players").then(argument("toggle", toggle()).executes(c -> {
                    CONFIG_DATA.safety.abortOnPlayer = getToggle(c, "toggle");
                    c.getSource().getEmbed().title("LootBot Safety").description("abort on players " + toggleStr(CONFIG_DATA.safety.abortOnPlayer));
                }))))
            .then(literal("update").then(argument("toggle", toggle()).executes(c -> {
                CONFIG_DATA.update.auto = getToggle(c, "toggle");
                c.getSource().getEmbed().title("LootBot Self-Update " + toggleStrCaps(CONFIG_DATA.update.auto));
            })));
    }

    private static String kitLine(Map<String, Integer> kit) {
        return kit.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
    }

    private static LootModule module() {
        return MODULE.get(LootModule.class);
    }

    private static LootJob job() {
        return module().job();
    }
}
