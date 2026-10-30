package dev.lootbot;

import com.zenith.cache.data.chunk.Chunk;
import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.zenith.Globals.CACHE;

/** Finds lootable containers in the chunks the proxy holds, from the chunks' block entity lists. */
final class ContainerScan {

    enum Kind { CHEST, TRAPPED_CHEST, BARREL, SHULKER }

    record Spot(int x, int y, int z, Kind kind, boolean doubleChest) {
        String key() {
            return x + "," + y + "," + z;
        }

        double dist2(double px, double py, double pz) {
            double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
            return dx * dx + dy * dy + dz * dz;
        }

        String describe() {
            return kind.name().toLowerCase(Locale.ROOT) + (doubleChest ? " (double)" : "") + " at " + x + " " + y + " " + z;
        }
    }

    private ContainerScan() {}

    /**
     * Containers within {@code radius} (XZ) of the centre, one entry per double chest, ordered for
     * looting: {@code nearest} by distance from the bot, {@code shulkersFirst} placed shulker boxes
     * then the rest by distance, {@code doublesFirst} double chests then the rest by distance.
     */
    static List<Spot> scan(int cx, int cz, int radius, String order, double px, double py, double pz) {
        List<Chunk> chunks;
        try {
            chunks = new ArrayList<>(CACHE.getChunkCache().getCache().values());
        } catch (RuntimeException e) {
            return List.of(); // changed under us on the network thread: the next scan gets it
        }
        List<Spot> raw = new ArrayList<>();
        long r2 = (long) radius * radius;
        for (Chunk chunk : chunks) {
            int baseX = chunk.getX() << 4, baseZ = chunk.getZ() << 4;
            if (baseX + 16 < cx - radius || baseX > cx + radius || baseZ + 16 < cz - radius || baseZ > cz + radius) {
                continue;
            }
            List<BlockEntityInfo> entities;
            synchronized (chunk.getBlockEntities()) {
                entities = new ArrayList<>(chunk.getBlockEntities());
            }
            for (BlockEntityInfo be : entities) {
                Kind kind = kindOf(be.getType());
                if (kind == null) {
                    continue;
                }
                int x = baseX + be.getX(), y = be.getY(), z = baseZ + be.getZ();
                long dx = x - cx, dz = z - cz;
                if (dx * dx + dz * dz > r2) {
                    continue;
                }
                raw.add(new Spot(x, y, z, kind, false));
            }
        }
        // Double chests: two chests of the same kind side by side are one inventory, opened once.
        Set<String> all = new HashSet<>();
        for (Spot s : raw) {
            all.add(s.kind() + ":" + s.key());
        }
        Set<String> skip = new HashSet<>();
        List<Spot> out = new ArrayList<>();
        for (Spot s : raw) {
            String k = s.kind() + ":" + s.key();
            if (skip.contains(k)) {
                continue;
            }
            boolean pair = false;
            if (s.kind() == Kind.CHEST || s.kind() == Kind.TRAPPED_CHEST) {
                int[][] n = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] d : n) {
                    String nk = s.kind() + ":" + (s.x() + d[0]) + "," + s.y() + "," + (s.z() + d[1]);
                    if (all.contains(nk) && !skip.contains(nk)) {
                        skip.add(nk);
                        pair = true;
                        break;
                    }
                }
            }
            out.add(new Spot(s.x(), s.y(), s.z(), s.kind(), pair));
        }
        Comparator<Spot> byDist = Comparator.comparingDouble(s -> s.dist2(px, py, pz));
        Comparator<Spot> cmp = switch (order) {
            case "shulkersFirst" -> Comparator.comparing((Spot s) -> s.kind() != Kind.SHULKER).thenComparing(byDist);
            case "doublesFirst" -> Comparator.comparing((Spot s) -> !s.doubleChest()).thenComparing(byDist);
            default -> byDist;
        };
        out.sort(cmp);
        return out;
    }

    /** The block at the spot is still that container (it may have been broken since the scan). */
    static boolean stillThere(Spot s) {
        Block b = World.getBlock(s.x(), s.y(), s.z());
        if (b == null) {
            return false;
        }
        String n = b.name();
        return switch (s.kind()) {
            case CHEST -> n.endsWith(":chest") || n.equals("chest");
            case TRAPPED_CHEST -> n.endsWith("trapped_chest");
            case BARREL -> n.endsWith("barrel");
            case SHULKER -> n.endsWith("shulker_box");
        };
    }

    private static Kind kindOf(BlockEntityType t) {
        if (t == null) {
            return null;
        }
        return switch (t) {
            case CHEST -> Kind.CHEST;
            case TRAPPED_CHEST -> Kind.TRAPPED_CHEST;
            case BARREL -> Kind.BARREL;
            case SHULKER_BOX -> Kind.SHULKER;
            default -> null;
        };
    }
}
