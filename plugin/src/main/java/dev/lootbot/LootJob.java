package dev.lootbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.player.World;
import com.zenith.mc.dimension.DimensionData;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INVENTORY;
import static com.zenith.Globals.PLAYER_LISTS;

/**
 * The loot job: asks the learner for a known base, travels there, finds the containers in the
 * loaded chunks, opens each one and takes what is worth taking, breaks and picks up placed shulker
 * boxes, and goes home to unload when the inventory fills. Every target visit is an episode sent
 * back to the learner with the settings used, the time spent and what was taken.
 *
 * Everything here runs on the bot tick (the client event loop), only while no player is connected
 * to the proxy. Commands from other threads only leave requests that the tick picks up.
 */
final class LootJob {

    enum Phase { OFF, IDLE, ASKING, UPDATING, TRAVEL, SETTLE, LOOT, HOME, DEPOSIT }

    private enum Step { PICK, OPENING, WAIT_OPEN, TAKING, CLOSING, BREAKING, PICKUP }

    /** Ticks a container may take to show up after the click. */
    private static final int OPEN_WAIT = 80;
    /** ZenithProxy sends one inventory action every few ticks (actionDelayTicks, 5 by default). */
    private static final int TICKS_PER_ACTION = 6;
    private static final int CLOSE_WAIT = 100;
    private static final int PICKUP_WAIT = 200;
    /** Picking up a broken shulker box never takes the bot further than this from it. */
    private static final double PICKUP_RANGE = 12;
    /** Health over the abort line needed before new work is taken. */
    private static final int HEAL_MARGIN = 6;

    private static final ItemData[] SHULKER_BOXES = {
        ItemRegistry.SHULKER_BOX, ItemRegistry.WHITE_SHULKER_BOX, ItemRegistry.ORANGE_SHULKER_BOX,
        ItemRegistry.MAGENTA_SHULKER_BOX, ItemRegistry.LIGHT_BLUE_SHULKER_BOX, ItemRegistry.YELLOW_SHULKER_BOX,
        ItemRegistry.LIME_SHULKER_BOX, ItemRegistry.PINK_SHULKER_BOX, ItemRegistry.GRAY_SHULKER_BOX,
        ItemRegistry.LIGHT_GRAY_SHULKER_BOX, ItemRegistry.CYAN_SHULKER_BOX, ItemRegistry.PURPLE_SHULKER_BOX,
        ItemRegistry.BLUE_SHULKER_BOX, ItemRegistry.BROWN_SHULKER_BOX, ItemRegistry.GREEN_SHULKER_BOX,
        ItemRegistry.RED_SHULKER_BOX, ItemRegistry.BLACK_SHULKER_BOX,
    };

    private final LootConfig config;
    private final LearnerClient learner;
    private final Consumer<String> log;
    private final Consumer<String> command;
    private final Supplier<String> botName;
    private final int inventoryPriority;

    // Requests from commands (other threads), handled on the next tick.
    private volatile boolean startRequested;
    private volatile boolean stopRequested;

    private volatile Phase phase = Phase.OFF;
    private int phaseTicks;
    private long nextAskAt;
    private volatile String lastNote = "";

    // The work in hand.
    private LearnerClient.Target target;
    private Params params = Params.defaults();
    private String paramsId = "";
    private CompletableFuture<LearnerClient.Work> asking;
    private CompletableFuture<Boolean> downloading;
    private String updatingTo;
    private String skipVersion;
    private long skipVersionUntil;

    // Travel.
    private int[] travelTo;      // {x, z}
    private int travelArrive;
    private int travelLimitTicks;
    private boolean travelByCommand;
    private boolean travelByElytra;
    private int travelRestarts;
    private double bestDist;
    private int bestDistTick;

    // Looting and unloading.
    private List<ContainerScan.Spot> spots = new ArrayList<>();
    private int spotIndex;
    private ContainerScan.Spot spot;
    private Step step;
    private int stepTicks;
    private PathingRequestFuture pathing;
    private RequestFuture invRequest;
    private List<InventoryAction> invActions;
    private int invWait;
    private final Map<String, Integer> tries = new HashMap<>();
    private final Set<String> finished = new HashSet<>();
    private int shulkersBefore;

    // Home.
    private List<ContainerScan.Spot> homeSpots = new ArrayList<>();
    private int homeIndex;
    private int deposited;
    /** No trip home before this time (the home containers were full, or the trip failed). */
    private long homeBlockedUntil;

    // The episode.
    private Episode ep;
    private volatile String abortReason;
    private boolean kitWarned;

    LootJob(LootConfig config, LearnerClient learner, Consumer<String> log, Consumer<String> command,
            Supplier<String> botName, int inventoryPriority) {
        this.config = config;
        this.learner = learner;
        this.log = log;
        this.command = command;
        this.botName = botName;
        this.inventoryPriority = inventoryPriority;
    }

    // ------------------------------------------------------------------ control (any thread)

    String requestStart() {
        if (!learner.configured()) {
            return "No learner set: 'loot learner <url> <token>' first";
        }
        if (config.kit.isEmpty()) {
            return "No kit saved: put the bot's gear in its inventory and run 'loot kit' first,"
                    + " so it knows what is gear and what is loot";
        }
        config.active = true;
        startRequested = true;
        return "Loot job starting: it asks the learner for a target on its next tick";
    }

    String requestStop() {
        boolean was = phase != Phase.OFF || config.active;
        config.active = false;
        stopRequested = true;
        return was ? "Loot job stopping" : "The loot job was not running";
    }

    /** Back in game after a login: a running job asks for work again. */
    void onOnline() {
        if (config.active) {
            startRequested = true;
        }
    }

    /** Disconnected (network thread): the tick is not running, so this only leaves a request. */
    void onOffline() {
        abortReason = "disconnected";
    }

    /** Someone not on the friends list came into render distance (network thread). */
    void onStranger(String name) {
        if (config.safety.abortOnPlayer && (phase == Phase.TRAVEL || phase == Phase.SETTLE || phase == Phase.LOOT)) {
            abortReason = "player " + name + " nearby";
        }
    }

    boolean busy() {
        return phase == Phase.LOOT || phase == Phase.DEPOSIT;
    }

    // ------------------------------------------------------------------ the tick

    void tick() {
        if (stopRequested) {
            stopRequested = false;
            startRequested = false;
            if (ep != null) {
                finishEpisode("stopped", false);
            }
            stopMoving();
            closeContainer();
            to(Phase.OFF);
            note("stopped");
        }
        if (startRequested) {
            startRequested = false;
            if (ep != null) {
                finishEpisode("disconnected", false); // left over from before a relog
            }
            if (config.active) {
                stopMoving();
                to(Phase.IDLE);
                nextAskAt = System.currentTimeMillis() + 3_000;
            }
            abortReason = null;
        }
        if (phase == Phase.OFF) {
            return;
        }
        phaseTicks++;
        if (ep != null) {
            ep.ticks++;
        }
        if (health() <= 0) {
            onDeath();
            return;
        }
        switch (phase) {
            case IDLE -> tickIdle();
            case ASKING -> tickAsking();
            case UPDATING -> tickUpdating();
            case TRAVEL -> tickTravel();
            case SETTLE -> tickSettle();
            case LOOT -> tickLoot();
            case HOME -> tickHome();
            case DEPOSIT -> tickDeposit();
            default -> { }
        }
    }

    private void onDeath() {
        if (phase == Phase.IDLE && ep == null) {
            return; // already handled: waiting for the respawn
        }
        if (ep != null) {
            ep.deaths++;
            finishEpisode("died", false);
        } else {
            learner.event(botName.get(), "died", "died while " + phase.name().toLowerCase(Locale.ROOT)
                    + " at " + (int) px() + ", " + (int) pz());
        }
        stopMoving();
        note("died; waiting for the respawn");
        to(Phase.IDLE);
        nextAskAt = System.currentTimeMillis() + 15_000;
    }

    // ------------------------------------------------------------------ asking

    private void tickIdle() {
        if (!config.active || System.currentTimeMillis() < nextAskAt) {
            return;
        }
        abortReason = null;
        if (health() < config.safety.abortHealth + HEAL_MARGIN) {
            note("healing before the next target (health " + (int) health() + ")");
            idleFor(30);
            return;
        }
        String missing = missingKit();
        if (missing != null) {
            if (!kitWarned) {
                kitWarned = true;
                learner.event(botName.get(), "kit_missing", "no " + missing + " in the inventory: not taking targets");
            }
            note("kit incomplete (no " + missing + "): waiting; refill it or save a new kit with 'loot kit'");
            idleFor(300);
            return;
        }
        kitWarned = false;
        if (Items.freeSlots() <= Math.max(2, params.reserveSlots + 1)) {
            // No room for loot: unload first, or wait when there is nowhere to unload.
            if (config.home.set && !depositSlots().isEmpty() && System.currentTimeMillis() >= homeBlockedUntil) {
                note("inventory full: going home to unload");
                goHome();
            } else {
                note(config.home.set ? "inventory full and home is unavailable for now: waiting"
                        : "inventory full and no home set ('loot home here'): waiting");
                idleFor(300);
            }
            return;
        }
        asking = learner.next(botName.get(), BuildConstants.VERSION, state());
        to(Phase.ASKING);
    }

    /** The first kit item with none left in the inventory, or null when the kit is all there. */
    private String missingKit() {
        Map<String, Integer> have = Items.counts();
        for (String item : config.kit.keySet()) {
            if (have.getOrDefault(item, 0) <= 0) {
                return item;
            }
        }
        return null;
    }

    private void tickAsking() {
        if (asking == null) {
            idleFor(5);
            return;
        }
        if (!asking.isDone()) {
            if (phaseTicks > 20 * 60) {
                note("the learner did not answer in a minute");
                asking = null;
                idleFor(config.learner.pollSeconds);
            }
            return;
        }
        LearnerClient.Work w = asking.getNow(null);
        asking = null;
        if (w == null) {
            idleFor(config.learner.pollSeconds);
            return;
        }
        if (wantsUpdate(w)) {
            Path own = ownJar();
            if (own == null) {
                note("the learner wants version " + w.version + " but this jar's location is unknown: carrying on");
                skipVersion(w.version);
            } else {
                note("updating to " + w.version);
                updatingTo = w.version;
                downloading = learner.download(w.jar, own.getParent().resolve("LootBot-" + w.version + ".jar"));
                to(Phase.UPDATING);
                return;
            }
        }
        if (w.target == null) {
            note(w.note == null ? "no target from the learner" : w.note);
            idleFor(w.retrySeconds > 0 ? w.retrySeconds : config.learner.pollSeconds);
            return;
        }
        target = w.target;
        params = w.params == null ? Params.defaults() : w.params;
        paramsId = w.paramsId == null ? "" : w.paramsId;
        ep = new Episode(target, params, paramsId);
        tries.clear();
        finished.clear();
        if (!dimensionName().equals(target.dim == null ? "overworld" : target.dim)) {
            finishEpisode("wrong_dimension", false);
            return;
        }
        note("target " + target.id + " at " + target.x + ", " + target.z + (target.label == null ? "" : " (" + target.label + ")"));
        startTravel(target.x, target.z, params.arriveRadius);
        to(Phase.TRAVEL);
    }

    private boolean wantsUpdate(LearnerClient.Work w) {
        if (!config.update.auto || w.version == null || w.jar == null || w.version.equals(BuildConstants.VERSION)) {
            return false;
        }
        return !(w.version.equals(skipVersion) && System.currentTimeMillis() < skipVersionUntil);
    }

    private void skipVersion(String v) {
        skipVersion = v;
        skipVersionUntil = System.currentTimeMillis() + 30 * 60_000L;
    }

    private void tickUpdating() {
        if (downloading == null) {
            idleFor(5);
            return;
        }
        if (!downloading.isDone()) {
            if (phaseTicks > 20 * 60 * 6) {
                note("the jar download took too long");
                downloading = null;
                skipVersion(updatingTo);
                idleFor(5);
            }
            return;
        }
        boolean ok = downloading.getNow(false);
        downloading = null;
        Path own = ownJar();
        if (!ok || own == null) {
            note("update to " + updatingTo + " failed; staying on " + BuildConstants.VERSION + " for now");
            skipVersion(updatingTo);
            idleFor(5);
            return;
        }
        Path fresh = own.getParent().resolve("LootBot-" + updatingTo + ".jar");
        if (!fresh.equals(own)) {
            try {
                // The old jar is kept as a .bak (ZenithProxy loads only .jar files): the watchdog
                // (deploy/watchdog.py) puts it back if the new version is bad or never reports in.
                try (var old = Files.newDirectoryStream(own.getParent(), "LootBot-*.jar.bak")) {
                    for (Path p : old) {
                        Files.deleteIfExists(p);
                    }
                }
                Files.move(own, own.resolveSibling(own.getFileName() + ".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                note("could not set the old jar aside (" + e.getMessage() + "): update cancelled");
                try {
                    Files.deleteIfExists(fresh);
                } catch (Exception ignored) {
                    // the next try replaces it
                }
                skipVersion(updatingTo);
                idleFor(5);
                return;
            }
        }
        note("jar " + updatingTo + " installed: restarting the proxy");
        // The restart waits for the proxy to shut down: never on the tick thread.
        CompletableFuture.runAsync(() -> command.accept("update"));
        to(Phase.IDLE);
        nextAskAt = System.currentTimeMillis() + 120_000;
    }

    private static Path ownJar() {
        try {
            var src = LootBotPlugin.class.getProtectionDomain().getCodeSource();
            if (src == null) {
                return null;
            }
            Path p = Path.of(src.getLocation().toURI());
            return p.toString().endsWith(".jar") && Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ travel

    private void startTravel(int x, int z, int arrive) {
        travelTo = new int[] {x, z};
        travelArrive = Math.max(2, arrive);
        double d = distXZ(x, z);
        int minutes = 3 + (int) Math.ceil(d / Math.max(50, config.travel.blocksPerMinute));
        travelLimitTicks = minutes * 60 * 20;
        travelRestarts = 0;
        bestDist = d;
        bestDistTick = 0;
        travelByElytra = false;
        String cmd = config.travel.command;
        travelByCommand = cmd != null && !cmd.isBlank() && d > arrive;
        if (d <= arrive) {
            return;
        }
        if (travelByCommand) {
            command.accept(cmd.replace("{x}", String.valueOf(x)).replace("{z}", String.valueOf(z)));
        } else {
            pathTowards(x, z, d);
        }
    }

    private void pathTowards(int x, int z, double d) {
        travelByElytra = config.travel.elytraOver > 0 && d > config.travel.elytraOver && dimensionName().equals("overworld");
        try {
            pathing = travelByElytra ? BARITONE.elytraTo(x, z) : BARITONE.pathTo(x, z);
        } catch (RuntimeException e) {
            // Elytra pathing refuses some starts (out of bounds, not ready): walk instead.
            travelByElytra = false;
            try {
                pathing = BARITONE.pathTo(x, z);
            } catch (RuntimeException e2) {
                pathing = null;
                note("the pathfinder refused the trip: " + e2.getMessage());
            }
        }
    }

    private static boolean landed() {
        return BOT.isOnGround() && !BOT.isFallFlying();
    }

    /** 1 arrived, 0 travelling, -1 failed. */
    private int travelState() {
        double d = distXZ(travelTo[0], travelTo[1]);
        if (d < bestDist - 32) {
            bestDist = d;
            bestDistTick = phaseTicks;
        }
        if (phaseTicks > travelLimitTicks || phaseTicks - bestDistTick > 20 * 60 * 5) {
            return -1; // out of time, or no ground gained for five minutes
        }
        // Never "arrived" in the air: a flight lands by itself, and cutting it off mid-glide drops the bot.
        if (!landed()) {
            return 0;
        }
        if (d <= travelArrive) {
            if (travelByCommand) {
                String stop = config.travel.stopCommand;
                if (stop != null && !stop.isBlank()) {
                    command.accept(stop);
                }
            } else {
                stopMoving();
            }
            return 1;
        }
        if (!travelByCommand && phaseTicks > 40 && !BARITONE.isActive()) {
            // The flight landed short, or the walk gave up: walk the rest.
            if (++travelRestarts > 4) {
                return -1;
            }
            boolean wasElytra = travelByElytra;
            if (wasElytra) {
                travelByElytra = false;
                try {
                    pathing = BARITONE.pathTo(travelTo[0], travelTo[1]);
                } catch (RuntimeException e) {
                    return -1;
                }
            } else {
                pathTowards(travelTo[0], travelTo[1], d);
            }
        }
        return 0;
    }

    private void stopTravel() {
        if (travelByCommand) {
            String stop = config.travel.stopCommand;
            if (stop != null && !stop.isBlank()) {
                command.accept(stop);
            }
        }
        stopMoving();
    }

    private void tickTravel() {
        if ("disconnected".equals(abortReason)) {
            abortReason = null;
            stopTravel();
            finishEpisode("disconnected", false);
            return;
        }
        if (health() <= config.safety.abortHealth) {
            stopTravel();
            ep.notes.add("aborted: health " + (int) health() + " on the way");
            finishEpisode("aborted_health", false);
            return;
        }
        int s = travelState();
        if (s == 0) {
            return;
        }
        ep.travelTicks = ep.ticks;
        if (s < 0) {
            stopTravel();
            finishEpisode("travel_failed", false);
            return;
        }
        note("arrived at target " + target.id + "; waiting for chunks");
        to(Phase.SETTLE);
    }

    // ------------------------------------------------------------------ at the target

    private void tickSettle() {
        if (checkAbort()) {
            return;
        }
        if (phaseTicks < params.settleTicks) {
            return;
        }
        spots = new ArrayList<>(ContainerScan.scan(target.x, target.z, params.searchRadius, params.order,
                params.lootTrapped, px(), py(), pz()));
        spotIndex = 0;
        ep.containersFound = spots.size();
        if (spots.isEmpty()) {
            finishEpisode("no_containers", false);
            return;
        }
        note(spots.size() + " container(s) found at target " + target.id);
        shulkersBefore = countShulkers();
        stepTo(Step.PICK);
        to(Phase.LOOT);
    }

    /** A stranger at the base (from the event, or anyone already standing there), or low health. */
    private boolean checkAbort() {
        String why = abortReason;
        if (why == null && config.safety.abortOnPlayer) {
            String name = strangerNear();
            if (name != null) {
                why = "player " + name + " nearby";
            }
        }
        if (why == null && health() <= config.safety.abortHealth) {
            why = "health " + (int) health();
        }
        if (why == null) {
            return false;
        }
        abortReason = null;
        stopMoving();
        closeContainer();
        ep.notes.add("aborted: " + why);
        String outcome = why.equals("disconnected") ? "disconnected"
                : why.startsWith("player") ? "aborted_player" : "aborted_health";
        finishEpisode(outcome, !why.equals("disconnected"));
        return true;
    }

    /** The name of a player in the entity cache who is not the bot, a friend or whitelisted. */
    static String strangerNear() {
        Map<UUID, com.zenith.cache.data.entity.EntityPlayer> players;
        try {
            players = new HashMap<>(CACHE.getEntityCache().getPlayers());
        } catch (RuntimeException e) {
            return null;
        }
        int self = CACHE.getPlayerCache().getEntityId();
        for (var e : players.entrySet()) {
            var p = e.getValue();
            if (p == null || p.isSelfPlayer() || p.getEntityId() == self) {
                continue;
            }
            UUID id = e.getKey();
            if (id != null && (PLAYER_LISTS.getFriendsList().contains(id) || PLAYER_LISTS.getWhitelist().contains(id))) {
                continue;
            }
            return CACHE.getTabListCache().get(id).map(entry -> entry.getName()).orElse("someone");
        }
        return null;
    }

    private void tickLoot() {
        if (checkAbort()) {
            return;
        }
        stepTicks++;
        switch (step) {
            case PICK -> pickNext();
            case OPENING -> tickOpening();
            case WAIT_OPEN -> tickWaitOpen();
            case TAKING -> tickTaking();
            case CLOSING -> tickClosing();
            case BREAKING -> tickBreaking();
            case PICKUP -> tickPickup();
        }
    }

    private void pickNext() {
        if (containerOpen()) {
            // Never click the next container while one is still open: what shows up must be the new one.
            if (stepTicks % 10 == 1) {
                closeContainer();
            }
            if (stepTicks > CLOSE_WAIT) {
                stepTicks = 0;
            }
            return;
        }
        if (Items.freeSlots() <= params.reserveSlots) {
            finishEpisode(ep.containersOpened > 0 ? "full" : "no_room", true);
            return;
        }
        if (ep.containersTried >= params.maxContainers) {
            endTarget("container limit reached");
            return;
        }
        while (spotIndex < spots.size()) {
            ContainerScan.Spot s = spots.get(spotIndex);
            if (finished.contains(s.key()) || !ContainerScan.stillThere(s)) {
                spotIndex++;
                continue;
            }
            spot = s;
            break;
        }
        if (spotIndex >= spots.size()) {
            endTarget("every container done");
            return;
        }
        ep.containersTried++;
        tries.merge(spot.key(), 1, Integer::sum);
        if (spot.kind() == ContainerScan.Kind.SHULKER && params.breakShulkers) {
            shulkersBefore = countShulkers();
            pathing = BARITONE.breakBlock(spot.x(), spot.y(), spot.z(), true);
            stepTo(Step.BREAKING);
        } else {
            pathing = BARITONE.rightClickBlock(spot.x(), spot.y(), spot.z());
            stepTo(Step.OPENING);
        }
    }

    private void tickOpening() {
        // Nothing was open when the click went out (pickNext waits for that), so an open container is this one.
        if (containerOpen()) {
            stepTo(Step.WAIT_OPEN);
            return;
        }
        if (pathing != null && pathing.isDone()) {
            if (!pathing.getNow()) {
                spotFailed("could not reach or click it");
                return;
            }
            stepTo(Step.WAIT_OPEN); // clicked: the container shows up shortly
            return;
        }
        if (stepTicks > params.openTimeoutTicks) {
            stopMoving();
            spotFailed("path and open timed out");
        }
    }

    /** The open container, once it is a real one with its contents in; null until then. */
    private static Container openContainer() {
        var cache = CACHE.getPlayerCache().getInventoryCache();
        if (cache.getOpenContainerId() == 0) {
            return null;
        }
        Container c = cache.getOpenContainer();
        if (c == null || c.getContainerId() == 0 || c.getSize() <= 36) {
            return null;
        }
        return c;
    }

    private void tickWaitOpen() {
        Container c = openContainer();
        if (c == null || stepTicks < 6) {
            if (stepTicks > OPEN_WAIT) {
                spotFailed(containerOpen() ? "it opened but its contents never came" : "clicked but it did not open (blocked lid?)");
            }
            return;
        }
        takeFrom(c);
    }

    private void takeFrom(Container c) {
        int id = c.getContainerId();
        int slots = c.getSize() - 36;
        List<int[]> picks = new ArrayList<>(); // {slot, rank}
        int items = 0;
        for (int i = 0; i < slots; i++) {
            ItemStack s = c.getItemStack(i);
            if (Items.blank(s)) {
                continue;
            }
            items++;
            int rank = rank(s);
            if (rank >= 0) {
                picks.add(new int[] {i, rank});
            }
        }
        picks.sort((a, b) -> Integer.compare(a[1], b[1]));
        int room = Math.max(0, Items.freeSlots() - params.reserveSlots);
        List<InventoryAction> actions = new ArrayList<>();
        for (int k = 0; k < picks.size() && k < room; k++) {
            actions.add(new ShiftClick(id, picks.get(k)[0], ShiftClickItemAction.LEFT_CLICK));
        }
        ep.containersOpened++;
        if (items == 0) {
            ep.containersEmpty++;
        }
        if (slots == 54) {
            ep.doubleChests++;
            // The other half of a double chest is the same inventory: done too.
            for (ContainerScan.Spot other : spots) {
                if (other.kind() == spot.kind() && other.y() == spot.y()
                        && Math.abs(other.x() - spot.x()) + Math.abs(other.z() - spot.z()) == 1) {
                    finished.add(other.key());
                }
            }
        }
        submitActions(actions);
        stepTo(Step.TAKING);
    }

    /** Lower is taken first; -1 is left. Shulker boxes first, then wanted items in the learner's order. */
    private int rank(ItemStack s) {
        if (Items.isShulker(s)) {
            return Items.contents(s).isEmpty() ? 900 : 0;
        }
        String n = Items.name(s);
        int i = params.wanted == null ? -1 : params.wanted.indexOf(n);
        if (i >= 0) {
            return 1 + i;
        }
        return "all".equals(params.take) ? 1000 : -1;
    }

    private RequestFuture submit(List<InventoryAction> actions) {
        return INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this).actions(actions).priority(inventoryPriority).build());
    }

    private void submitActions(List<InventoryAction> actions) {
        if (actions.isEmpty()) {
            invActions = null;
            invRequest = null;
            invWait = 0;
            return;
        }
        invActions = actions;
        invRequest = submit(actions);
        invWait = actions.size() * TICKS_PER_ACTION + 40;
    }

    /**
     * Where the submitted clicks stand: 1 done, 0 still going, -1 refused for good. A refused request
     * (another module had the inventory) goes in again for a while.
     */
    private int clicksState() {
        if (invRequest == null) {
            return 1;
        }
        if (invRequest.isDone()) {
            if (invRequest.isAccepted()) {
                return 1;
            }
            if (stepTicks < 60) {
                invRequest = submit(invActions);
                return 0;
            }
            return -1;
        }
        return stepTicks > invWait ? -1 : 0;
    }

    private void tickTaking() {
        int s = clicksState();
        if (s == 0 || stepTicks < 8) {
            return;
        }
        if (s < 0) {
            closeContainer();
            spotFailed("the inventory clicks did not go through");
            return;
        }
        if (invActions != null) {
            ep.stacksTaken += invActions.size();
        }
        finished.add(spot.key());
        closeContainer();
        stepTo(Step.CLOSING);
    }

    private void tickClosing() {
        if (containerOpen()) {
            if (stepTicks % 10 == 0) {
                closeContainer();
            }
            if (stepTicks < CLOSE_WAIT) {
                return;
            }
        }
        spotIndex++;
        stepTo(Step.PICK); // PICK waits for a container still open
    }

    private void tickBreaking() {
        if (!ContainerScan.stillThere(spot)) {
            stopMoving();
            pathing = BARITONE.pickup(SHULKER_BOXES);
            stepTo(Step.PICKUP);
            return;
        }
        if (pathing != null && pathing.isDone() && !pathing.getNow()) {
            spotFailed("could not reach or break the shulker box");
            return;
        }
        if (stepTicks > params.openTimeoutTicks + 100) {
            stopMoving();
            spotFailed("breaking the shulker box timed out");
        }
    }

    private void tickPickup() {
        if (countShulkers() > shulkersBefore) {
            stopMoving();
            ep.shulkersBroken++;
            ep.containersOpened++;
            finished.add(spot.key());
            spotIndex++;
            stepTo(Step.PICK);
            return;
        }
        double dx = px() - spot.x() - 0.5, dz = pz() - spot.z() - 0.5;
        boolean wandered = dx * dx + dz * dz > PICKUP_RANGE * PICKUP_RANGE;
        if (wandered || stepTicks > PICKUP_WAIT || (pathing != null && pathing.isDone() && stepTicks > 40)) {
            stopMoving();
            ep.failures.add(failure(wandered ? "the broken shulker box was out of reach" : "broke the shulker box but did not pick it up"));
            finished.add(spot.key());
            spotIndex++;
            stepTo(Step.PICK);
        }
    }

    private void spotFailed(String why) {
        closeContainer();
        ep.containersFailed++;
        ep.failures.add(failure(why));
        if (tries.getOrDefault(spot.key(), 0) >= params.triesPerContainer) {
            finished.add(spot.key());
            spotIndex++;
        }
        // Otherwise it stays at spotIndex and is tried again.
        stepTo(Step.PICK);
    }

    private JsonObject failure(String why) {
        JsonObject f = new JsonObject();
        f.addProperty("reason", why);
        f.addProperty("kind", spot.kind().name().toLowerCase(Locale.ROOT));
        f.addProperty("x", spot.x());
        f.addProperty("y", spot.y());
        f.addProperty("z", spot.z());
        return f;
    }

    private void endTarget(String why) {
        ep.notes.add(why);
        boolean took = !ep.lootSoFar().isEmpty();
        boolean goHome = Items.freeSlots() <= Math.max(params.reserveSlots + 4, 6);
        finishEpisode(took ? "looted" : (ep.containersOpened > 0 ? "empty" : "unreachable"), goHome);
    }

    // ------------------------------------------------------------------ home

    /** Home to unload, only when there is a home, a saved kit (so gear is never unloaded) and loot. */
    private boolean goHome() {
        if (!config.home.set || config.kit.isEmpty() || depositSlots().isEmpty()
                || System.currentTimeMillis() < homeBlockedUntil) {
            return false;
        }
        startTravel(config.home.x, config.home.z, 4);
        to(Phase.HOME);
        return true;
    }

    private void tickHome() {
        int s = health() <= config.safety.abortHealth ? -1 : travelState();
        if (s == 0) {
            return;
        }
        if (s < 0) {
            stopTravel();
            homeBlockedUntil = System.currentTimeMillis() + 30 * 60_000L;
            note("could not get home to unload; trying again in 30 minutes");
            learner.event(botName.get(), "home_failed", "could not get home from " + (int) px() + ", " + (int) pz());
            idleFor(30);
            return;
        }
        homeSpots = new ArrayList<>(ContainerScan.scan(config.home.x, config.home.z, config.home.radius, "nearest",
                false, px(), py(), pz()));
        homeSpots.removeIf(sp -> sp.kind() == ContainerScan.Kind.SHULKER);
        homeIndex = 0;
        deposited = 0;
        stepTo(Step.PICK);
        to(Phase.DEPOSIT);
    }

    private void tickDeposit() {
        stepTicks++;
        switch (step) {
            case PICK -> {
                if (containerOpen()) {
                    if (stepTicks % 10 == 1) {
                        closeContainer();
                    }
                    if (stepTicks > CLOSE_WAIT) {
                        stepTicks = 0;
                    }
                    return;
                }
                if (depositSlots().isEmpty() || homeIndex >= homeSpots.size()) {
                    boolean left = !depositSlots().isEmpty();
                    String msg = "unloaded " + deposited + " stack(s) at home" + (left ? "; the containers there are full" : "");
                    if (left) {
                        homeBlockedUntil = System.currentTimeMillis() + 30 * 60_000L;
                    }
                    note(msg);
                    learner.event(botName.get(), "deposit", msg);
                    idleFor(2);
                    return;
                }
                spot = homeSpots.get(homeIndex);
                pathing = BARITONE.rightClickBlock(spot.x(), spot.y(), spot.z());
                stepTo(Step.OPENING);
            }
            case OPENING -> {
                if (containerOpen() || (pathing != null && pathing.isDone() && pathing.getNow())) {
                    stepTo(Step.WAIT_OPEN);
                } else if ((pathing != null && pathing.isDone()) || stepTicks > 400) {
                    stopMoving();
                    homeIndex++;
                    stepTo(Step.PICK);
                }
            }
            case WAIT_OPEN -> {
                Container c = openContainer();
                if (c == null || stepTicks < 6) {
                    if (stepTicks > OPEN_WAIT) {
                        homeIndex++;
                        stepTo(Step.PICK);
                    }
                    return;
                }
                int slots = c.getSize() - 36;
                int room = 0;
                for (int i = 0; i < slots; i++) {
                    if (Items.blank(c.getItemStack(i))) {
                        room++;
                    }
                }
                List<InventoryAction> actions = new ArrayList<>();
                for (int inv : depositSlots()) {
                    if (actions.size() >= room) {
                        break;
                    }
                    actions.add(new ShiftClick(c.getContainerId(), slots + (inv - Items.FIRST), ShiftClickItemAction.LEFT_CLICK));
                }
                submitActions(actions);
                stepTo(Step.TAKING);
            }
            case TAKING -> {
                int s = clicksState();
                if (s == 0 || stepTicks < 8) {
                    return;
                }
                if (s > 0 && invActions != null) {
                    deposited += invActions.size();
                }
                closeContainer();
                homeIndex++;
                stepTo(Step.CLOSING);
            }
            case CLOSING -> {
                if (containerOpen()) {
                    if (stepTicks % 10 == 0) {
                        closeContainer();
                    }
                    if (stepTicks < CLOSE_WAIT) {
                        return;
                    }
                }
                stepTo(Step.PICK);
            }
            default -> stepTo(Step.PICK);
        }
    }

    /**
     * Inventory slots (9-44) holding nothing but loot. A stack is kept while the kit count for its
     * item is not yet reached, even when loot has been merged into it: gear is never unloaded.
     */
    private List<Integer> depositSlots() {
        Map<String, Integer> kept = new HashMap<>();
        List<Integer> out = new ArrayList<>();
        if (config.kit.isEmpty()) {
            return out; // no kit saved: everything might be gear
        }
        for (int c = Items.FIRST; c <= Items.LAST; c++) {
            ItemStack s = Items.stackAt(c);
            if (Items.blank(s)) {
                continue;
            }
            String n = Items.name(s);
            int allowed = config.kit.getOrDefault(n, 0);
            int have = kept.getOrDefault(n, 0);
            if (have < allowed) {
                kept.put(n, have + s.getAmount());
            } else {
                out.add(c);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ episodes

    private void finishEpisode(String outcome, boolean goHome) {
        if (ep == null) {
            return;
        }
        stopMoving();
        closeContainer();
        ep.outcome = outcome;
        learner.report(ep.toJson(botName.get(), BuildConstants.VERSION));
        String loot = ep.lootSummary();
        note("target " + target.id + ": " + outcome + (loot.isEmpty() ? "" : " (" + loot + ")"));
        ep = null;
        if (!(goHome && config.active && goHome())) {
            idleFor(2);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void idleFor(int seconds) {
        to(config.active ? Phase.IDLE : Phase.OFF);
        nextAskAt = System.currentTimeMillis() + seconds * 1000L;
    }

    private JsonObject state() {
        JsonObject s = new JsonObject();
        s.addProperty("x", px());
        s.addProperty("y", py());
        s.addProperty("z", pz());
        s.addProperty("dim", dimensionName());
        s.addProperty("health", health());
        s.addProperty("freeSlots", Items.freeSlots());
        s.addProperty("homeSet", config.home.set);
        s.addProperty("autoUpdate", config.update.auto);
        if (skipVersion != null && System.currentTimeMillis() < skipVersionUntil) {
            s.addProperty("updateFailed", skipVersion);
        }
        JsonArray inv = new JsonArray();
        for (var e : Items.counts().entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("item", e.getKey());
            o.addProperty("count", e.getValue());
            inv.add(o);
        }
        s.add("inventory", inv);
        return s;
    }

    private static boolean containerOpen() {
        return CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() != 0;
    }

    private void closeContainer() {
        if (containerOpen()) {
            INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                    .actions(List.of(new CloseContainer())).priority(inventoryPriority + 1).build());
        }
    }

    private static void stopMoving() {
        if (BARITONE.isActive()) {
            BARITONE.stop();
        }
    }

    private static int countShulkers() {
        int n = 0;
        for (int c = Items.FIRST; c <= Items.LAST; c++) {
            if (Items.isShulker(Items.stackAt(c))) {
                n++;
            }
        }
        return n;
    }

    static double px() {
        return CACHE.getPlayerCache().getX();
    }

    static double py() {
        return CACHE.getPlayerCache().getY();
    }

    static double pz() {
        return CACHE.getPlayerCache().getZ();
    }

    private static double distXZ(int x, int z) {
        double dx = x + 0.5 - px(), dz = z + 0.5 - pz();
        return Math.sqrt(dx * dx + dz * dz);
    }

    static double health() {
        var p = CACHE.getPlayerCache().getThePlayer();
        if (p == null || p.getHealth() == null) {
            return 20;
        }
        return p.getHealth();
    }

    static String dimensionName() {
        DimensionData d = World.getCurrentDimension();
        String n = d == null ? "overworld" : d.name();
        if (n.endsWith("the_nether")) {
            return "nether";
        }
        if (n.endsWith("the_end")) {
            return "end";
        }
        return "overworld";
    }

    private void stepTo(Step next) {
        step = next;
        stepTicks = 0;
    }

    private void to(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private void note(String s) {
        lastNote = s;
        log.accept("LootBot: " + s);
    }

    Phase phase() {
        return phase;
    }

    /** What the job is doing, for the dashboard's live view (read on the tick thread). */
    JsonObject viewInfo() {
        JsonObject o = new JsonObject();
        Phase p = phase;
        o.addProperty("phase", p.name().toLowerCase(Locale.ROOT));
        if ((p == Phase.LOOT || p == Phase.DEPOSIT) && step != null) {
            o.addProperty("step", step.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        o.addProperty("note", lastNote);
        LearnerClient.Target t = target;
        if (t != null && p != Phase.IDLE && p != Phase.OFF && p != Phase.ASKING) {
            JsonObject to = new JsonObject();
            to.addProperty("id", t.id);
            to.addProperty("x", t.x);
            to.addProperty("z", t.z);
            to.addProperty("label", t.label);
            o.add("target", to);
        }
        if (travelTo != null && (p == Phase.TRAVEL || p == Phase.HOME)) {
            JsonObject g = new JsonObject();
            g.addProperty("x", travelTo[0]);
            g.addProperty("z", travelTo[1]);
            o.add("goal", g);
        }
        if (p == Phase.LOOT || p == Phase.SETTLE || p == Phase.DEPOSIT) {
            List<ContainerScan.Spot> list = p == Phase.DEPOSIT ? homeSpots : spots;
            JsonArray arr = new JsonArray();
            for (ContainerScan.Spot s : list) {
                JsonObject c = new JsonObject();
                c.addProperty("x", s.x());
                c.addProperty("z", s.z());
                c.addProperty("kind", s.kind().name().toLowerCase(Locale.ROOT));
                String state = finished.contains(s.key()) ? "done" : "todo";
                if (s == spot && (step == Step.OPENING || step == Step.WAIT_OPEN || step == Step.TAKING
                        || step == Step.BREAKING || step == Step.PICKUP)) {
                    state = "now";
                }
                c.addProperty("state", state);
                arr.add(c);
            }
            o.add("containers", arr);
        }
        if (ep != null) {
            o.addProperty("loot", ep.lootSummary());
        }
        return o;
    }

    /** Read from the command thread: only the volatile fields and the inventory cache. */
    String statusLine() {
        Phase p = phase;
        StringBuilder sb = new StringBuilder(p.name().toLowerCase(Locale.ROOT));
        LearnerClient.Target t = target;
        if (t != null && p != Phase.IDLE && p != Phase.OFF && p != Phase.ASKING) {
            sb.append("; target ").append(t.id).append(" at ").append(t.x).append(", ").append(t.z);
        }
        sb.append("; ").append(Items.freeSlots()).append(" free slots; version ").append(BuildConstants.VERSION);
        String last = lastNote;
        if (!last.isEmpty()) {
            sb.append("\nLast: ").append(last);
        }
        return sb.toString();
    }
}
