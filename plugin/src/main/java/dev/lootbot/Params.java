package dev.lootbot;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings the learner tunes. One set comes with every target and is echoed back in the
 * episode, so the learner knows which settings produced which result. Field names match the
 * learner's search space (learner/space.json).
 */
public class Params {

    /** Blocks around the target scanned for containers. */
    public int searchRadius = 48;
    /** Ticks waited after arriving for chunks to load before the scan counts. */
    public int settleTicks = 100;
    /** How close (XZ) counts as arrived. */
    public int arriveRadius = 16;
    /** Most containers tried at one target. */
    public int maxContainers = 40;
    /** nearest | shulkersFirst | doublesFirst */
    public String order = "shulkersFirst";
    /** Ticks a path-and-open may take before the container is given up on. */
    public int openTimeoutTicks = 300;
    /** Tries per container before it is skipped. */
    public int triesPerContainer = 2;
    /** Placed shulker boxes are broken and picked up whole (needs a pickaxe). */
    public boolean breakShulkers = true;
    /** Stop looting and go home when this few inventory slots are free. */
    public int reserveSlots = 1;
    /** all | wanted: with "wanted", only shulker boxes and the items in wanted are taken. */
    public String take = "wanted";
    /** Item ids (without minecraft:) worth taking, most valuable first. */
    public List<String> wanted = new ArrayList<>(List.of(
            "enchanted_golden_apple", "elytra", "totem_of_undying", "netherite_block", "netherite_ingot",
            "netherite_scrap", "ancient_debris", "diamond_block", "diamond", "beacon", "nether_star",
            "end_crystal", "obsidian", "golden_apple", "experience_bottle", "firework_rocket",
            "emerald_block", "gold_block", "iron_block", "ender_pearl", "netherite_sword",
            "netherite_pickaxe", "netherite_helmet", "netherite_chestplate", "netherite_leggings",
            "netherite_boots", "diamond_sword", "diamond_pickaxe", "diamond_chestplate", "enchanted_book"));

    static Params defaults() {
        return new Params();
    }
}
