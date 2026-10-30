package dev.lootbot;

import java.util.HashMap;
import java.util.Map;

/**
 * Saved as plugins/config/lootbot.json. Only the connection and local behaviour live here: the
 * tunable settings come from the learner with every target (see {@link Params}).
 */
public class LootConfig {

    public boolean enabled = false;
    /** Running: picks up targets from the learner whenever the bot is idle. Kept across restarts. */
    public boolean active = false;

    public Learner learner = new Learner();
    public Travel travel = new Travel();
    public Home home = new Home();
    public Safety safety = new Safety();
    public Update update = new Update();
    public View view = new View();
    /**
     * The kit: item counts the bot keeps (elytra, rockets, food, pickaxe...). Saved from the inventory
     * with 'loot kit'; everything beyond it is unloaded at home.
     */
    public Map<String, Integer> kit = new HashMap<>();
    /** Inventory requests go in at this priority (GridFly's hold sits at 20000). */
    public int inventoryPriority = 20002;

    public static class Learner {
        /** e.g. http://10.0.0.5:25700 */
        public String url = "";
        public String token = "";
        /** Seconds between asks for work while idle or when the learner is down. */
        public int pollSeconds = 30;
    }

    public static class Travel {
        /**
         * A proxy command that takes the bot to a spot, run as the account owner. {x} and {z} are
         * filled in, e.g. "gridfly goto {x} {z}" when GridFly or HighwayRunner is on the same proxy.
         * Empty: ZenithProxy's own pathfinder walks (or flies, see elytra).
         */
        public String command = "";
        /** Sent before the loot job uses the pathfinder itself, so the travel command lets go. */
        public String stopCommand = "";
        /** With no travel command: ZenithProxy's elytra pathing for trips over this many blocks (0: never). */
        public int elytraOver = 0;
        /** Minimum travel time allowed before a trip counts as stuck, plus a minute per this many blocks. */
        public int blocksPerMinute = 400;
    }

    public static class Home {
        public boolean set = false;
        public int x;
        public int y;
        public int z;
        /** Containers within this many blocks of home take the loot. */
        public int radius = 8;
    }

    public static class Safety {
        /** Health at or under this ends the target and heads home. */
        public int abortHealth = 8;
        /** Someone not on the friends list in render distance ends the target. */
        public boolean abortOnPlayer = true;
    }

    public static class View {
        /** Sends the dashboard's live view (a map of the blocks around the bot, its pose, players near). */
        public boolean enabled = true;
        /** Blocks each way from the bot. */
        public int radius = 48;
        /** Ticks between views (20 is one a second). */
        public int intervalTicks = 20;
    }

    public static class Update {
        /** Installs the jar version the learner asks for and restarts the proxy between targets. */
        public boolean auto = true;
    }
}
