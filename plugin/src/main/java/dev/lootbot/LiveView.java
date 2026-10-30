package dev.lootbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zenith.cache.data.chunk.Chunk;
import com.zenith.mc.block.Block;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.zenith.Globals.BLOCK_DATA;
import static com.zenith.Globals.CACHE;

/**
 * The dashboard's live view: about once a second, a top-down map of the blocks around the bot
 * (each column's map colour and height, read from below a ceiling a little over the bot's head so
 * a roof does not hide the floor it stands on), the bot's pose, nearby players, and what the loot
 * job is doing. Built on a worker thread and sent to the learner, which keeps the latest one.
 */
final class LiveView {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lootbot-live-view");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private final LootConfig config;
    private final LearnerClient learner;
    private final AtomicBoolean busy = new AtomicBoolean();
    private int ticks;

    LiveView(LootConfig config, LearnerClient learner) {
        this.config = config;
        this.learner = learner;
    }

    /** Every client tick (with or without a player on the proxy). {@code job} fills in the job's part. */
    void tick(String bot, LootJob job) {
        LootConfig.View c = config.view;
        if (!c.enabled || !learner.configured() || ++ticks < Math.max(5, c.intervalTicks)) {
            return;
        }
        ticks = 0;
        if (CACHE.getPlayerCache().getThePlayer() == null || !busy.compareAndSet(false, true)) {
            return;
        }
        // The cheap parts on the tick thread; the block scan on the worker.
        JsonObject view = new JsonObject();
        double x = LootJob.px(), y = LootJob.py(), z = LootJob.pz();
        view.addProperty("bot", bot);
        view.addProperty("at", System.currentTimeMillis());
        view.addProperty("x", x);
        view.addProperty("y", y);
        view.addProperty("z", z);
        view.addProperty("yaw", CACHE.getPlayerCache().getYaw());
        view.addProperty("dim", LootJob.dimensionName());
        view.addProperty("health", LootJob.health());
        view.addProperty("freeSlots", Items.freeSlots());
        view.add("players", players());
        view.add("job", job.viewInfo());
        if (config.home.set) {
            JsonObject h = new JsonObject();
            h.addProperty("x", config.home.x);
            h.addProperty("z", config.home.z);
            view.add("home", h);
        }
        int radius = Math.max(16, Math.min(96, c.radius));
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        WORKER.execute(() -> {
            try {
                scan(view, bx, by, bz, radius);
                learner.view(view);
            } catch (RuntimeException ignored) {
                // the chunks changed under the scan: the next one gets it
            } finally {
                busy.set(false);
            }
        });
    }

    private static JsonArray players() {
        JsonArray out = new JsonArray();
        Map<UUID, com.zenith.cache.data.entity.EntityPlayer> players;
        try {
            players = new HashMap<>(CACHE.getEntityCache().getPlayers());
        } catch (RuntimeException e) {
            return out;
        }
        int self = CACHE.getPlayerCache().getEntityId();
        for (var e : players.entrySet()) {
            var p = e.getValue();
            if (p == null || p.isSelfPlayer() || p.getEntityId() == self) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("name", CACHE.getTabListCache().get(e.getKey()).map(t -> t.getName()).orElse("?"));
            o.addProperty("x", p.getX());
            o.addProperty("z", p.getZ());
            out.add(o);
        }
        return out;
    }

    /**
     * Columns from (bx - r, bz - r) to (bx + r, bz + r), row by row (z outer, x inner): one byte
     * of map colour and one signed byte of height relative to the bot each, base64. Colour 0 is
     * "nothing loaded or nothing there".
     */
    private static void scan(JsonObject view, int bx, int by, int bz, int r) {
        int size = 2 * r + 1;
        byte[] colours = new byte[size * size];
        byte[] heights = new byte[size * size];
        int ceiling = by + 6;
        Chunk chunk = null;
        int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;
        for (int dz = 0; dz < size; dz++) {
            int wz = bz - r + dz;
            for (int dx = 0; dx < size; dx++) {
                int wx = bx - r + dx;
                int cx = wx >> 4, cz = wz >> 4;
                if (cx != chunkX || cz != chunkZ) {
                    chunk = CACHE.getChunkCache().get(cx, cz);
                    chunkX = cx;
                    chunkZ = cz;
                }
                if (chunk == null) {
                    continue;
                }
                int lx = wx & 15, lz = wz & 15;
                int top = Math.min(ceiling, chunk.maxY());
                for (int yy = top; yy >= chunk.minY(); yy--) {
                    int state = chunk.getBlockStateId(lx, yy, lz);
                    if (state == 0) {
                        continue;
                    }
                    Block b = BLOCK_DATA.getBlockDataFromBlockStateId(state);
                    if (b == null || b.isAir() || b.mapColorId() == 0) {
                        continue;
                    }
                    int i = dz * size + dx;
                    colours[i] = (byte) b.mapColorId();
                    heights[i] = (byte) Math.max(-127, Math.min(127, yy - by));
                    break;
                }
            }
        }
        JsonObject grid = new JsonObject();
        grid.addProperty("x0", bx - r);
        grid.addProperty("z0", bz - r);
        grid.addProperty("size", size);
        grid.addProperty("colours", Base64.getEncoder().encodeToString(colours));
        grid.addProperty("heights", Base64.getEncoder().encodeToString(heights));
        view.add("grid", grid);
    }
}
