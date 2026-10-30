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
    private final AtomicBoolean eyesBusy = new AtomicBoolean();
    private int ticks;

    // The bot's eyes: the blocks around an anchor (sent when the bot has moved away from it or the
    // blocks may have changed), and the bot's pose against that anchor a few times a second.
    private static final int EYES_R = 20, EYES_UP = 10, EYES_DOWN = 8;
    private int eyesTicks, blocksTicks = 1_000_000;
    private volatile int version;
    private volatile int[] anchor;

    LiveView(LootConfig config, LearnerClient learner) {
        this.config = config;
        this.learner = learner;
    }

    /** Every client tick (with or without a player on the proxy). {@code job} fills in the job's part. */
    void tick(String bot, LootJob job) {
        LootConfig.View c = config.view;
        if (!c.enabled || !learner.configured()) {
            return;
        }
        tickEyes(bot, job);
        if (++ticks < Math.max(5, c.intervalTicks)) {
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

    private void tickEyes(String bot, LootJob job) {
        if (CACHE.getPlayerCache().getThePlayer() == null) {
            return;
        }
        blocksTicks++;
        if (++eyesTicks < 5) {
            return;
        }
        eyesTicks = 0;
        double x = LootJob.px(), y = LootJob.py(), z = LootJob.pz();
        int[] a = anchor;
        boolean moved = a == null || Math.abs(x - a[0]) > 6 || Math.abs(z - a[2]) > 6 || Math.abs(y - a[1]) > 4;
        if ((moved && blocksTicks >= 10 || blocksTicks >= 100) && eyesBusy.compareAndSet(false, true)) {
            blocksTicks = 0;
            int[] na = {(int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)};
            int nv = version + 1;
            JsonObject pose = pose(bot, job, nv, na);
            WORKER.execute(() -> {
                try {
                    JsonObject msg = new JsonObject();
                    msg.addProperty("bot", bot);
                    msg.add("blocks", blocks(na));
                    msg.add("pose", pose);
                    msg.addProperty("v", nv);
                    anchor = na;
                    version = nv;
                    learner.eyes(msg);
                } catch (RuntimeException ignored) {
                    // the chunks changed under the scan: the next one gets it
                } finally {
                    eyesBusy.set(false);
                }
            });
            return;
        }
        if (a != null) {
            JsonObject msg = new JsonObject();
            msg.addProperty("bot", bot);
            msg.addProperty("v", version);
            msg.add("pose", pose(bot, job, version, a));
            learner.eyes(msg);
        }
    }

    /** The bot's position against the anchor, where it looks, what it's doing, and what's moving near it. */
    private static JsonObject pose(String bot, LootJob job, int v, int[] a) {
        JsonObject p = new JsonObject();
        p.addProperty("v", v);
        p.addProperty("x", LootJob.px() - a[0]);
        p.addProperty("y", LootJob.py() - a[1]);
        p.addProperty("z", LootJob.pz() - a[2]);
        p.addProperty("yaw", CACHE.getPlayerCache().getYaw());
        p.addProperty("pitch", CACHE.getPlayerCache().getPitch());
        JsonObject info = job.viewInfo();
        String step = info.has("step") ? info.get("phase").getAsString() + " - " + info.get("step").getAsString()
                : info.get("phase").getAsString();
        if (info.has("note") && !info.get("note").getAsString().isEmpty()) {
            step += "  -  " + info.get("note").getAsString();
        }
        p.addProperty("step", step);
        JsonArray ents = new JsonArray();
        int self = CACHE.getPlayerCache().getEntityId();
        try {
            for (var e : new java.util.ArrayList<>(CACHE.getEntityCache().getEntities().values())) {
                if (e == null || e.getEntityId() == self) {
                    continue;
                }
                double dx = e.getX() - a[0], dy = e.getY() - a[1], dz = e.getZ() - a[2];
                if (Math.abs(dx) > EYES_R + 4 || Math.abs(dz) > EYES_R + 4 || dy < -EYES_DOWN - 2 || dy > EYES_UP + 2) {
                    continue;
                }
                String type = e.getEntityType() == null ? "" : e.getEntityType().name();
                JsonObject o = new JsonObject();
                if (e instanceof com.zenith.cache.data.entity.EntityPlayer pl) {
                    if (pl.isSelfPlayer()) {
                        continue;
                    }
                    o.addProperty("t", "player");
                    o.addProperty("n", CACHE.getTabListCache().get(pl.getUuid()).map(t -> t.getName()).orElse("player"));
                } else if (type.equals("ITEM")) {
                    o.addProperty("t", "item");
                } else if (type.equals("ENDER_PEARL")) {
                    o.addProperty("t", "pearl");
                } else if (type.endsWith("MINECART") || type.equals("ITEM_FRAME") || type.equals("ARMOR_STAND")) {
                    continue;
                } else {
                    o.addProperty("t", "mob");
                }
                o.addProperty("x", dx);
                o.addProperty("y", dy);
                o.addProperty("z", dz);
                o.addProperty("yaw", e.getYaw());
                ents.add(o);
                if (ents.size() >= 40) {
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            // the entity cache changed under us
        }
        p.add("ents", ents);
        return p;
    }

    /** The blocks around the anchor by name, y then z then x, run-length coded against a palette. */
    private static JsonObject blocks(int[] a) {
        int w = 2 * EYES_R + 1;
        Map<String, Integer> index = new HashMap<>();
        JsonArray palette = new JsonArray();
        index.put("air", 0);
        palette.add("air");
        JsonArray rle = new JsonArray();
        int last = -1, run = 0;
        Chunk chunk = null;
        int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;
        for (int dy = -EYES_DOWN; dy <= EYES_UP; dy++) {
            int wy = a[1] + dy;
            for (int dz = -EYES_R; dz <= EYES_R; dz++) {
                int wz = a[2] + dz;
                for (int dx = -EYES_R; dx <= EYES_R; dx++) {
                    int wx = a[0] + dx;
                    int cx = wx >> 4, cz = wz >> 4;
                    if (cx != chunkX || cz != chunkZ) {
                        chunk = CACHE.getChunkCache().get(cx, cz);
                        chunkX = cx;
                        chunkZ = cz;
                    }
                    int id = 0;
                    if (chunk != null && wy >= chunk.minY() && wy <= chunk.maxY()) {
                        int state = chunk.getBlockStateId(wx & 15, wy, wz & 15);
                        if (state != 0) {
                            Block b = BLOCK_DATA.getBlockDataFromBlockStateId(state);
                            if (b != null && !b.isAir()) {
                                String n = b.name().startsWith("minecraft:") ? b.name().substring(10) : b.name();
                                Integer k = index.get(n);
                                if (k == null) {
                                    k = palette.size();
                                    index.put(n, k);
                                    palette.add(n);
                                }
                                id = k;
                            }
                        }
                    }
                    if (id == last) {
                        run++;
                    } else {
                        if (last >= 0) {
                            rle.add(last);
                            rle.add(run);
                        }
                        last = id;
                        run = 1;
                    }
                }
            }
        }
        rle.add(last);
        rle.add(run);
        JsonObject o = new JsonObject();
        o.addProperty("r", EYES_R);
        o.addProperty("up", EYES_UP);
        o.addProperty("down", EYES_DOWN);
        o.add("palette", palette);
        o.add("rle", rle);
        return o;
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
