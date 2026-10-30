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
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INVENTORY;

/**
 * The loot job: asks the learner for a known base, travels there, finds the containers in the
 * loaded chunks, opens each one and takes what is worth taking, breaks and picks up placed shulker
 * boxes, and goes home to unload when the inventory fills. Every target visit is an episode sent
 * back to the learner with the settings used, the time spent and what was taken. Runs on the bot
 * tick (only while no player is connected to the proxy).
 */
final class LootJob {

    enum Phase { OFF, IDLE, ASKING, UPDATING, TRAVEL, SETTLE, LOOT, HOME, DEPOSIT }

    private enum Step { PICK, OPENING, WAIT_OPEN, TAKING, CLOSING, BREAKING, PICKUP }

    /** Ticks a container may take to show up after the click. */
    private static final int OPEN_WAIT = 60;
    private static final int TAKE_WAIT = 80;
    private static final int PICKUP_WAIT = 160;

    private final LootConfig config;
    private final LearnerClient learner;
    private final Consumer<String> log;
    private final Consumer<String> command;
    private final Supplier<String> botName;
    private final int inventoryPriority;

    private Phase phase = Phase.OFF;
    private int phaseTicks;
    private long nextAskAt;
    private String lastNote = "";

    // The work in hand.
    private LearnerClient.Target target;
    private Params params = Params.defaults();
    private String paramsId = "";
    private CompletableFuture<LearnerClient.Work> asking;
    private CompletableFuture<Boolean> downloading;
    private String skipVersion;
    private long skipVersionUntil;

    // Travel.
    private int[] travelTo;      // {x, z}
    private int travelArrive;
    private int travelLimitTicks;
    private boolean travelByCommand;
    private int travelRestarts;
    private double bestDist;
    private int bestDistTick;

    // Looting.
    private List<ContainerScan.Spot> spots = new ArrayList<>();
    private int spotIndex;
    private ContainerScan.Spot spot;
    private Step step;
    private int stepTicks;
    private PathingRequestFuture pathing;
    private RequestFuture invRequest;
    private List<InventoryAction> invActions;
    private final Map<String, Integer> tries = new HashMap<>();
    private final Set<String> finished = new HashSet<>();
    private int shulkersBefore;

    // Depositing.
    private List<ContainerScan.Spot> homeSpots = new ArrayList<>();
    private int homeIndex;
    private int deposited;
    /** The home containers were full: no trip home before this time. */
    private long homeFullUntil;

    // The episode.
    private Episode ep;
    private volatile String abortReason;

    LootJob(LootConfig config, LearnerClient learner, Consumer<String> log, Consumer<String> command,
            Supplier<String> botName, int inventoryPriority) {
        this.config = config;
        this.learner = learner;
        this.log = log;
        this.command = command;
        this.botName = botName;
        this.inventoryPriority = inventoryPriority;
    }

    // ------------------------------------------------------------------ control

    String start() {
        if (!learner.configured()) {
            return "No learner set: 'loot learner <url> <token>' first";
        }
        config.active = true;
        if (phase == Phase.OFF) {
            to(Phase.IDLE);
            nextAskAt = 0;
        }
        return "Loot job started: asking the learner for a target";
    }

    String stop() {
        boolean was = phase != Phase.OFF;
        config.active = false;
        if (ep != null) {
            finishEpisode("stopped", false);
        }
        stopMoving();
        closeContainer();
        to(Phase.OFF);
        return was ? "Loot job stopped" : "The loot job was not running";
    }

    /** Back in game after a login or a respawn: a running job asks for work again. */
    void onOnline() {
        if (config.active) {
            if (ep != null) {
                finishEpisode("disconnected", false);
            }
            to(Phase.IDLE);
            nextAskAt = System.currentTimeMillis() + 5_000;
        }
    }

    void onOffline() {
        if (ep != null) {
            finishEpisode("disconnected", false);
        }
        if (phase != Phase.OFF) {
            to(config.active ? Phase.IDLE : Phase.OFF);
        }
    }

    /** Someone not on the friends list came into render distance (network thread). */
    void onStranger(String name) {
        if (config.safety.abortOnPlayer && (phase == Phase.SETTLE || phase == Phase.LOOT)) {
            abortReason = "player " + name + " nearby";
        }
    }

    boolean busy() {
        return phase == Phase.LOOT || phase == Phase.DEPOSIT;
    }

    // ------------------------------------------------------------------ the tick

    void tick() {
        if (phase == Phase.OFF) {
            return;
        }
        phaseTicks++;
        if (ep != null) {
            ep.ticks++;
        }
        if (health() <= 0) {
            if (ep != null) {
                ep.deaths++;
                finishEpisode("died", false);
                stopMoving();
                to(Phase.IDLE);
                nextAskAt = System.currentTimeMillis() + 15_000;
            }
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

    // ------------------------------------------------------------------ asking

    private void tickIdle() {
        if (!config.active || System.currentTimeMillis() < nextAskAt) {
            return;
        }
        if (Items.freeSlots() <= Math.max(2, params.reserveSlots + 1)) {
            // No room for loot: unload first, or wait when there is nowhere to unload.
            if (config.home.set && !depositSlots().isEmpty() && System.currentTimeMillis() >= homeFullUntil) {
                note("inventory full: going home to unload");
                startTravel(config.home.x, config.home.z, 4);
                to(Phase.HOME);
            } else {
                note(config.home.set ? "inventory full and the home containers are full: waiting"
                        : "inventory full and no home set ('loot home'): waiting");
                idleFor(300);
            }
            return;
        }
        JsonObject state = state();
        asking = learner.next(botName.get(), BuildConstants.VERSION, state);
        to(Phase.ASKING);
    }

    private void tickAsking() {
        if (!asking.isDone()) {
            if (phaseTicks > 20 * 60) {
                note("the learner did not answer in a minute");
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
            Path dir = ownJar() == null ? null : ownJar().getParent();
            if (dir == null) {
                note("the learner wants version " + w.version + " but this jar's location is unknown: carrying on");
                skipVersion(w.version);
            } else {
                note("updating to " + w.version);
                downloading = learner.download(w.jar, dir.resolve("LootBot-" + w.version + ".jar"));
                skipVersion = w.version; // remembered for the restart check below
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
            idleFor(5);
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
        if (!downloading.isDone()) {
            if (phaseTicks > 20 * 60 * 6) {
                note("the jar download took too long");
                skipVersion(skipVersion);
                idleFor(5);
            }
            return;
        }
        boolean ok = downloading.getNow(false);
        downloading = null;
        Path own = ownJar();
        if (!ok || own == null) {
            note("update to " + skipVersion + " failed; staying on " + BuildConstants.VERSION + " for now");
            skipVersion(skipVersion);
            idleFor(5);
            return;
        }
        Path fresh = own.getParent().resolve("LootBot-" + skipVersion + ".jar");
        if (!fresh.equals(own)) {
            try {
                // The old jar is kept as a .bak (ZenithProxy loads only .jar files): the watchdog
                // (deploy/watchdog.py) puts it back if the new version never reports in.
                try (var old = Files.newDirectoryStream(own.getParent(), "LootBot-*.jar.bak")) {
                    for (Path p : old) {
                        Files.deleteIfExists(p);
                    }
                }
                Files.move(own, own.resolveSibling(own.getFileName() + ".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                note("could not remove the old jar (" + e.getMessage() + "): update cancelled");
                try {
                    Files.deleteIfExists(fresh);
                } catch (Exception ignored) {
                    // left for the next try to replace
                }
                skipVersion(skipVersion);
                idleFor(5);
                return;
            }
        }
        note("jar " + skipVersion + " installed: restarting the proxy");
        command.accept("update");
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
        if (config.travel.elytraOver > 0 && d > config.travel.elytraOver && dimensionName().equals("overworld")) {
            pathing = BARITONE.elytraTo(x, z);
        } else {
            pathing = BARITONE.pathTo(x, z);
        }
    }

    /** 1 arrived, 0 travelling, -1 failed. */
    private int travelState() {
        double d = distXZ(travelTo[0], travelTo[1]);
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
        if (d < bestDist - 32) {
            bestDist = d;
            bestDistTick = phaseTicks;
        }
        if (phaseTicks > travelLimitTicks) {
            return -1;
        }
        // No ground gained for five minutes: stuck (the travel command's own watchdogs had their chance).
        if (phaseTicks - bestDistTick > 20 * 60 * 5) {
            return -1;
        }
        if (!travelByCommand && phaseTicks > 40 && !BARITONE.isActive()) {
            if (++travelRestarts > 4) {
                return -1;
            }
            pathTowards(travelTo[0], travelTo[1], d);
        }
        return 0;
    }

    private void tickTravel() {
        int s = travelState();
        if (s == 0) {
            return;
        }
        ep.travelTicks = ep.ticks;
        if (s < 0) {
            stopTravel();
            finishEpisode("travel_failed", false);
            idleFor(5);
            return;
        }
        note("arrived at target " + target.id + "; waiting for chunks");
        abortReason = null;
        to(Phase.SETTLE);
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

    // ------------------------------------------------------------------ at the target

    private void tickSettle() {
        if (checkAbort()) {
            return;
        }
        if (phaseTicks < params.settleTicks) {
            return;
        }
        rescan();
        ep.containersFound = spots.size();
        if (spots.isEmpty()) {
            finishEpisode("no_containers", false);
            idleFor(2);
            return;
        }
        note(spots.size() + " container(s) found at target " + target.id);
        shulkersBefore = countShulkers();
        step = Step.PICK;
        to(Phase.LOOT);
    }

    private void rescan() {
        spots = new ArrayList<>(ContainerScan.scan(target.x, target.z, params.searchRadius, params.order, px(), py(), pz()));
        spotIndex = 0;
    }

    private boolean checkAbort() {
        String why = abortReason;
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
        String outcome = why.startsWith("player") ? "aborted_player" : "aborted_health";
        finishEpisode(outcome, config.home.set && !depositSlots().isEmpty());
        return true;
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
        if (Items.freeSlots() <= params.reserveSlots) {
            finishEpisode(ep.containersOpened > 0 ? "full" : "no_room", config.home.set);
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

    private void tickWaitOpen() {
        if (!containerOpen()) {
            if (stepTicks > OPEN_WAIT) {
                spotFailed("clicked but it did not open (blocked lid?)");
            }
            return;
        }
        if (stepTicks < 6) {
            return; // its contents follow the open
        }
        takeFromOpen();
    }

    private void takeFromOpen() {
        Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        int id = c.getContainerId();
        int slots = Math.max(0, c.getSize() - 36);
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
        }
        if (actions.isEmpty()) {
            invRequest = null;
            invActions = null;
        } else {
            invActions = actions;
            invRequest = submit(actions);
            ep.stacksTaken += actions.size();
        }
        finished.add(spot.key());
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

    /** A refused inventory request (another module had the inventory) goes in again for a while. */
    private boolean resubmitIfRefused() {
        if (invRequest != null && invActions != null && invRequest.isDone() && !invRequest.isAccepted() && stepTicks < 40) {
            invRequest = submit(invActions);
            return true;
        }
        return false;
    }

    private void tickTaking() {
        if (resubmitIfRefused()) {
            return;
        }
        boolean done = invRequest == null || invRequest.isDone();
        if (!done && stepTicks < TAKE_WAIT) {
            return;
        }
        if (stepTicks < 8) {
            return;
        }
        closeContainer();
        stepTo(Step.CLOSING);
    }

    private void tickClosing() {
        if (containerOpen() && stepTicks < 20) {
            return;
        }
        spotIndex++;
        stepTo(Step.PICK);
    }

    private void tickBreaking() {
        if (!ContainerScan.stillThere(spot)) {
            stopMoving();
            pathing = BARITONE.pickup();
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
        if (stepTicks > PICKUP_WAIT || (pathing != null && pathing.isDone() && stepTicks > 40)) {
            stopMoving();
            ep.failures.add(failure("broke the shulker box but did not pick it up"));
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
        boolean goHome = config.home.set && Items.freeSlots() <= Math.max(params.reserveSlots + 4, 6);
        finishEpisode(took ? "looted" : (ep.containersOpened > 0 ? "empty" : "unreachable"), goHome);
    }

    // ------------------------------------------------------------------ home

    private void tickHome() {
        int s = travelState();
        if (s == 0) {
            return;
        }
        if (s < 0) {
            note("could not get home to unload");
            learner.event(botName.get(), "home_failed", "could not get home from " + (int) px() + ", " + (int) pz());
            idleFor(30);
            return;
        }
        homeSpots = new ArrayList<>(ContainerScan.scan(config.home.x, config.home.z, config.home.radius, "nearest", px(), py(), pz()));
        homeSpots.removeIf(sp -> sp.kind() == ContainerScan.Kind.SHULKER);
        homeIndex = 0;
        deposited = 0;
        step = Step.PICK;
        to(Phase.DEPOSIT);
    }

    private void tickDeposit() {
        stepTicks++;
        switch (step) {
            case PICK -> {
                if (depositSlots().isEmpty() || homeIndex >= homeSpots.size()) {
                    boolean left = !depositSlots().isEmpty();
                    String msg = "unloaded " + deposited + " stack(s) at home" + (left ? "; the containers there are full" : "");
                    if (left) {
                        homeFullUntil = System.currentTimeMillis() + 30 * 60_000L;
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
                if (!containerOpen()) {
                    if (stepTicks > OPEN_WAIT) {
                        homeIndex++;
                        stepTo(Step.PICK);
                    }
                    return;
                }
                if (stepTicks < 6) {
                    return;
                }
                Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                int slots = Math.max(0, c.getSize() - 36);
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
                deposited += actions.size();
                invActions = actions.isEmpty() ? null : actions;
                invRequest = actions.isEmpty() ? null : submit(actions);
                stepTo(Step.TAKING);
            }
            case TAKING -> {
                if (resubmitIfRefused()) {
                    return;
                }
                if ((invRequest != null && !invRequest.isDone() && stepTicks < TAKE_WAIT) || stepTicks < 8) {
                    return;
                }
                closeContainer();
                homeIndex++;
                stepTo(Step.CLOSING);
            }
            case CLOSING -> {
                if (containerOpen() && stepTicks < 20) {
                    return;
                }
                stepTo(Step.PICK);
            }
            default -> stepTo(Step.PICK);
        }
    }

    /** Inventory slots (9-44) holding loot: everything beyond the kit counts saved with 'loot kit'. */
    private List<Integer> depositSlots() {
        Map<String, Integer> kept = new HashMap<>();
        List<Integer> out = new ArrayList<>();
        for (int c = Items.FIRST; c <= Items.LAST; c++) {
            ItemStack s = Items.stackAt(c);
            if (Items.blank(s)) {
                continue;
            }
            String n = Items.name(s);
            int allowed = config.kit.getOrDefault(n, 0);
            int have = kept.getOrDefault(n, 0);
            if (have + s.getAmount() <= allowed) {
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
        JsonObject json = ep.toJson(botName.get(), BuildConstants.VERSION);
        learner.report(json);
        String loot = ep.lootSummary();
        note("target " + target.id + ": " + outcome + (loot.isEmpty() ? "" : " (" + loot + ")"));
        ep = null;
        if (goHome && config.home.set && config.active) {
            startTravel(config.home.x, config.home.z, 4);
            to(Phase.HOME);
        } else if (phase != Phase.OFF) {
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
                    .actions(List.of(new CloseContainer())).priority(inventoryPriority).build());
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

    String statusLine() {
        StringBuilder sb = new StringBuilder(phase.name().toLowerCase(Locale.ROOT));
        if ((phase == Phase.LOOT || phase == Phase.DEPOSIT) && step != null) {
            sb.append(" (").append(step.name().toLowerCase(Locale.ROOT).replace('_', ' ')).append(")");
        }
        if (target != null && phase != Phase.IDLE && phase != Phase.OFF) {
            sb.append("; target ").append(target.id).append(" at ").append(target.x).append(", ").append(target.z);
        }
        if (phase == Phase.LOOT) {
            sb.append("; container ").append(Math.min(spotIndex + 1, spots.size())).append(" of ").append(spots.size());
        }
        if (ep != null) {
            String loot = ep.lootSummary();
            if (!loot.isEmpty()) {
                sb.append("; taken so far: ").append(loot);
            }
        }
        sb.append("; ").append(Items.freeSlots()).append(" free slots; version ").append(BuildConstants.VERSION);
        if (!lastNote.isEmpty()) {
            sb.append("\nLast: ").append(lastNote);
        }
        return sb.toString();
    }
}
