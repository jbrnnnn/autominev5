package com.automine;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * A* sederhana berbasis blok. Gerakan: jalan, naik 1 blok (lompat), turun 1 blok,
 * jatuh maks 3 blok. Blok penghalang boleh "dihancurkan" (tunneling) dengan biaya lebih tinggi.
 */
final class Pathfinder {
    private static final Direction[] HORIZ = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    private static final double INF = Double.POSITIVE_INFINITY;

    private static final class Node {
        final BlockPos pos; final long key; double g, f; Node parent;
        Node(BlockPos pos, double g, double f, Node parent) {
            this.pos = pos; this.key = pos.asLong(); this.g = g; this.f = f; this.parent = parent;
        }
    }

    private final ClientLevel lvl;
    private final BlockPos start;
    private final Vec3 targetCenter;
    private final double goalReach;
    private final int range;
    private final boolean avoidLava;
    private final boolean tunnel; // mode terowongan: murah menggali, heuristik agresif (hampir lurus)
    private final int lavaMargin;
    private final int hardLimit; // jarak lava <= hardLimit = terlarang
    private final Map<Long, Integer> lavaCache = new HashMap<>();

    private Pathfinder(ClientLevel lvl, BlockPos start, BlockPos target, double reach, int range, boolean tunnel) {
        this.tunnel = tunnel;
        this.lvl = lvl;
        this.start = start;
        this.targetCenter = Vec3.atCenterOf(target);
        // margin 0.75 supaya posisi pemain di dalam blok tetap dalam jangkauan nyata
        this.goalReach = Math.max(1.5, reach - 0.75);
        this.range = range;
        AutoMineConfig cfg = AutoMineConfig.get();
        this.avoidLava = cfg.avoidLava;
        this.lavaMargin = cfg.lavaMargin;
        // kalau start sudah menempel lava, tetap boleh melangkah ke sel yang sama dekat (kabur), tapi tidak lebih dekat
        this.hardLimit = avoidLava ? Math.min(1, lavaDist(start) - 1) : -1;
    }

    private int lavaDist(BlockPos p) {
        return lavaCache.computeIfAbsent(p.asLong(), k -> LavaScan.dist(lvl, p, lavaMargin));
    }

    /** Biaya tambahan dekat lava: 0 = aman, INF = terlarang (menempel lava), >0 = hindari bila bisa. */
    private double lavaCost(BlockPos to) {
        if (!avoidLava) return 0;
        int d = lavaDist(to);
        if (d <= hardLimit) return INF;
        if (d > lavaMargin) return 0;
        return (lavaMargin - d + 1) * 4.0;
    }

    /** @return daftar posisi kaki dari start sampai posisi yang cukup dekat ke target, atau null. */
    static List<BlockPos> find(ClientLevel lvl, BlockPos start, BlockPos target, double reach, int maxNodes, int range) {
        return find(lvl, start, target, reach, maxNodes, range, false);
    }

    static List<BlockPos> find(ClientLevel lvl, BlockPos start, BlockPos target, double reach, int maxNodes, int range, boolean tunnel) {
        return new Pathfinder(lvl, start, target, reach, range, tunnel).search(maxNodes);
    }

    private double eyeDist(BlockPos p) {
        double dx = p.getX() + 0.5 - targetCenter.x;
        double dy = p.getY() + 1.62 - targetCenter.y;
        double dz = p.getZ() + 0.5 - targetCenter.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double h(BlockPos p) { return Math.max(0, eyeDist(p) - goalReach) * (tunnel ? 4.5 : 1.3); }

    private List<BlockPos> search(int maxNodes) {
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<Long, Node> best = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        Node s = new Node(start, 0, h(start), null);
        open.add(s);
        best.put(s.key, s);
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxNodes) {
            Node n = open.poll();
            if (!closed.add(n.key)) continue;
            expanded++;
            if (eyeDist(n.pos) <= goalReach) return build(n);

            for (Direction d : HORIZ) {
                for (int dy = 1; dy >= -1; dy--) {
                    Object[] r = tryMove(n.pos, d, dy);
                    if (r == null) continue;
                    BlockPos to = (BlockPos) r[0];
                    double cost = (double) r[1];
                    if (Math.abs(to.getX() - start.getX()) > range || Math.abs(to.getZ() - start.getZ()) > range
                            || Math.abs(to.getY() - start.getY()) > range) continue;
                    long k = to.asLong();
                    if (closed.contains(k)) continue;
                    double lc = lavaCost(to);
                    if (lc == INF) continue;
                    double g = n.g + cost + lc;
                    Node old = best.get(k);
                    if (old == null || g < old.g) {
                        Node nn = new Node(to, g, g + h(to), n);
                        best.put(k, nn);
                        open.add(nn);
                    }
                }
            }
        }
        return null;
    }

    private List<BlockPos> build(Node goal) {
        List<BlockPos> out = new ArrayList<>();
        for (Node n = goal; n != null; n = n.parent) out.add(n.pos);
        Collections.reverse(out);
        return out;
    }

    /** @return {BlockPos tujuan, Double biaya} atau null kalau tidak mungkin. */
    private Object[] tryMove(BlockPos n, Direction d, int dy) {
        BlockPos c = n.relative(d);
        double cost = 1.0;
        double f;

        if (dy == 1) {
            if (!solidFloor(c)) return null;               // pijakan harus solid
            f = free(n.above(2)); if (f == INF) return null; cost += f;
            f = free(c.above(1)); if (f == INF) return null; cost += f;
            f = free(c.above(2)); if (f == INF) return null; cost += f;
            return new Object[]{c.above(1), cost + 1.5};
        }

        f = free(c.above(1)); if (f == INF) return null; cost += f;
        f = free(c);          if (f == INF) return null; cost += f;
        if (dy == -1) {
            f = free(c.below(1)); if (f == INF) return null; cost += f;
        }
        BlockPos feet = new BlockPos(c.getX(), n.getY() + dy, c.getZ());
        int fall = 0;
        while (!solidFloor(feet.below())) {
            BlockPos nb = feet.below();
            if (!passableStrict(nb)) return null;
            feet = nb;
            if (++fall > 3) return null;
        }
        if (dy == -1) cost += 1.0;
        return new Object[]{feet, cost + fall * 1.5};
    }

    /** Biaya agar sel bisa dilewati: 0 = kosong, >0 = harus dihancurkan, INF = mustahil. */
    private double free(BlockPos pos) {
        if (!lvl.hasChunkAt(pos)) return INF;
        BlockState st = lvl.getBlockState(pos);
        if (AutoBot.isDanger(st)) return INF;
        if (!st.getFluidState().isEmpty()) return INF;
        if (st.getCollisionShape(lvl, pos).isEmpty()) return 0;
        if (!AutoBot.allowDig(st)) return INF;
        if (!AutoBot.canBreak(lvl, pos, st)) return INF;
        if (tunnel) return 1.5 + st.getDestroySpeed(lvl, pos) * 0.3;
        return 3.0 + st.getDestroySpeed(lvl, pos) * 2.0;
    }

    private boolean solidFloor(BlockPos pos) {
        if (!lvl.hasChunkAt(pos)) return false;
        BlockState st = lvl.getBlockState(pos);
        if (AutoBot.isDanger(st)) return false;
        if (!st.getFluidState().isEmpty()) return false;
        return !st.getCollisionShape(lvl, pos).isEmpty();
    }

    private boolean passableStrict(BlockPos pos) {
        if (!lvl.hasChunkAt(pos)) return false;
        BlockState st = lvl.getBlockState(pos);
        if (AutoBot.isDanger(st)) return false;
        if (!st.getFluidState().isEmpty()) return false;
        return st.getCollisionShape(lvl, pos).isEmpty();
    }
}
