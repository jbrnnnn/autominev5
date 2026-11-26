package com.automine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Otak bot: scan -> pilih target -> pathfinding -> jalan -> hancurkan. */
public final class AutoBot {
    public enum Mode { OFF, MINE, WOOD }
    public record Found(BlockPos pos, int color) {}

    public static Mode mode = Mode.OFF;
    public static List<Found> found = List.of();
    public static BlockPos target = null;
    public static List<BlockPos> path = List.of();
    public static int pathIdx = 0;

    private static final Map<Block, Integer> wanted = new HashMap<>();
    private static final Map<Long, Long> blacklist = new HashMap<>();
    private static long ticks = 0;
    private static int scanTimer, stuck, offPath, repaths, noTarget, statusTimer, invTimer, lavaTimer, lavaGrace, lavaAlerts;
    private static Vec3 lastPos;

    // auto /repair
    private static long lastRepair = -100000;
    private static long repairCheckAt = -1;
    private static int repairBefore = -1, repairTries = 0, repairWait = 0;

    // memanjat pohon tinggi (pillar)
    private static BlockPos climbBase = null;
    private static boolean climbing = false, descending = false;
    private static BlockPos pillarFloor = null;
    private static int climbTicks = 0, descendWait = 0;
    private static final ArrayDeque<BlockPos> placed = new ArrayDeque<>();

    // strip mining (jelajah) & memungut drop
    private static Direction stripDir = null;
    private static BlockPos stripNext = null;
    private static int stripStuck = 0, pickTimer = 0, collectTicks = 0;
    private static Vec3 stripLast = null;
    private static BlockPos collectPos = null;

    // gali langsung ke target (tanpa A*) & anti-macet
    private static boolean directMode = false;
    private static BlockPos directNext = null;
    private static int directTicks = 0, directStuck = 0;
    private static Vec3 directLast = null;
    private static final ArrayDeque<BlockPos> directVisited = new ArrayDeque<>();

    // background
    private static Boolean oldPauseOnLostFocus = null;

    private AutoBot() {}

    // ---------------------------------------------------------------- kontrol

    public static void toggle(Mode m) {
        if (mode == m) stop("Dihentikan"); else start(m);
    }

    public static void start(Mode m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        rebuildWanted();
        if (m == Mode.MINE && wanted.isEmpty()) {
            msg(mc, "Pilih minimal 1 ore di menu dulu (RShift).");
            return;
        }
        mode = m;
        target = null; path = List.of(); pathIdx = 0;
        stuck = offPath = repaths = noTarget = 0;
        scanTimer = 0; invTimer = 0; statusTimer = 0;
        lavaTimer = 0; lavaGrace = 0; lavaAlerts = 0;
        lastPos = null;
        blacklist.clear();
        resetClimb();
        stripDir = null; stripNext = null; stripStuck = 0; stripLast = null;
        directMode = false; directNext = null; directVisited.clear();
        collectPos = null; collectTicks = 0; pickTimer = 0;
        lastRepair = -100000; repairCheckAt = -1; repairTries = 0; repairWait = 0;
        if (AutoMineConfig.get().background && mc.options != null) {
            if (oldPauseOnLostFocus == null) oldPauseOnLostFocus = mc.options.pauseOnLostFocus;
            mc.options.pauseOnLostFocus = false; // jangan pause saat window tidak fokus
        }
        msg(mc, m == Mode.MINE ? "Auto Mine MULAI" : "Auto Tebang Kayu MULAI");
    }

    public static void stop(String why) {
        Minecraft mc = Minecraft.getInstance();
        boolean was = mode != Mode.OFF;
        mode = Mode.OFF;
        target = null; path = List.of(); pathIdx = 0;
        if (mc.player != null && mc.options != null) releaseMove(mc);
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        resetClimb();
        stripNext = null; collectPos = null;
        if (oldPauseOnLostFocus != null && mc.options != null) {
            mc.options.pauseOnLostFocus = oldPauseOnLostFocus;
            oldPauseOnLostFocus = null;
        }
        if (was) msg(mc, "STOP: " + why);
    }

    public static void rebuildWanted() {
        wanted.clear();
        for (String n : AutoMineConfig.get().ores) {
            try {
                OreType t = OreType.valueOf(n);
                for (Block b : t.blocks) wanted.put(b, t.color);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private static void msg(Minecraft mc, String s) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[AutoMine] " + s), false);
    }

    // ---------------------------------------------------------------- tick

    public static void tick(Minecraft mc) {
        ticks++;
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        AutoMineConfig cfg = AutoMineConfig.get();

        if (p == null || lvl == null || mc.gameMode == null) {
            mode = Mode.OFF; found = List.of(); target = null; path = List.of();
            return;
        }

        boolean scanning = mode != Mode.OFF || (cfg.esp && cfg.espAlways);
        if (!scanning) {
            if (!found.isEmpty()) found = List.of();
            return;
        }
        if (--scanTimer <= 0) { scanTimer = 20; scan(mc); }
        if (mode == Mode.OFF) return;

        if (cfg.background) mc.options.pauseOnLostFocus = false;
        // pause hanya saat GUI mod / inventory terbuka; layar pause server tidak menghentikan bot
        if (mc.screen != null && !(cfg.background && mc.screen instanceof PauseScreen)) { releaseMove(mc); return; }

        // ---- pengaman
        if (p.isDeadOrDying()) { stop("Player mati"); return; }
        if (cfg.stopLowHealth && p.getHealth() <= cfg.minHealth) { stop("HP rendah"); return; }
        if (p.isInLava() || p.isOnFire()) { stop("Terbakar / kena lava"); return; }
        if (cfg.stopInvFull && --invTimer <= 0) {
            invTimer = 40;
            if (p.getInventory().getFreeSlot() < 0) { stop("Inventory penuh"); return; }
        }

        if (cfg.avoidLava && --lavaTimer <= 0) {
            lavaTimer = 4;
            if (lavaWatch(mc)) return;
        }

        if (durabilityWatch(mc)) return;

        if (--statusTimer <= 0) {
            statusTimer = 20;
            String m = mode == Mode.MINE ? "Mining" : "Tebang kayu";
            String t = target == null ? "mencari target" : "target " + target.toShortString();
            p.displayClientMessage(Component.literal("AutoMine | " + m + " | " + t + " | " + found.size() + " blok"), true);
        }

        // ---- target
        if (target != null && !validTarget(lvl, target)) {
            mc.gameMode.stopDestroyBlock();
            BlockPos done = target;
            target = null; path = List.of(); pathIdx = 0;
            if (mode == Mode.MINE && cfg.collectDrops && lvl.getBlockState(done).isAir()) startCollect(mc, done);
        }
        if (target == null && climbing) pickClimbTarget(mc);
        if (target == null) {
            if (climbing || climbBase != null) endClimb(mc);
            if (descending && descendTick(mc)) return;
            directMode = false; directNext = null;
            if (collectPos != null && collectTick(mc)) return;
            if (mode == Mode.WOOD || --pickTimer <= 0) { pickTimer = 10; pickTarget(mc); }
            if (target == null) {
                if (mode == Mode.MINE && cfg.explore) { noTarget = 0; stripTick(mc); return; }
                releaseMove(mc);
                if (++noTarget > 100) stop(mode == Mode.MINE ? "Ore tidak ditemukan di radius" : "Pohon tidak ditemukan di radius");
                return;
            }
            noTarget = 0;
            stripNext = null; collectPos = null;
        }

        // ---- sudah dalam jangkauan? hancurkan
        double reach = cfg.reach;
        if (p.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= reach * reach) {
            releaseMove(mc);
            if (!mineBlock(mc, target)) {
                blacklist(target, 2400);
                mc.gameMode.stopDestroyBlock();
                target = null; path = List.of(); pathIdx = 0;
            }
            return;
        }

        if (climbBase != null) {
            if (!climbing) {
                double hd = Math.hypot(p.getX() - (climbBase.getX() + 0.5), p.getZ() - (climbBase.getZ() + 0.5));
                if (hd < 1.7 && Math.abs(p.getY() - climbBase.getY()) < 1.5 && p.onGround()) startClimb(mc);
            }
            if (climbing) { climbTick(mc); return; }
        }

        if (directMode) directDigTick(mc); else followPath(mc);
    }

    // ---------------------------------------------------------------- lava

    /** Dipanggil tiap 4 tick. @return true kalau tick ini dipakai untuk menghindari lava. */
    private static boolean lavaWatch(Minecraft mc) {
        ClientLevel lvl = mc.level;
        BlockPos me = mc.player.blockPosition();

        if (lavaGrace > 0) { lavaGrace -= 4; return false; } // beri waktu mengikuti jalur kabur

        // 1) lava menempel di pemain (mis. baru mengalir): berhenti gali, cari jalur menjauh
        if (LavaScan.dist(lvl, me, 1) <= 1) {
            if (++lavaAlerts >= 3) { stop("Lava terlalu dekat"); return true; }
            msg(mc, "Lava dekat, mencari jalur menjauh...");
            releaseMove(mc);
            mc.gameMode.stopDestroyBlock();
            if (target != null) blacklist(target, 600);
            target = null; path = List.of(); pathIdx = 0;
            lavaGrace = 40;
            return true;
        }
        lavaAlerts = 0;

        // 2) lava muncul di jalur yang sudah dihitung
        int end = Math.min(path.size(), pathIdx + 6);
        for (int i = Math.max(0, pathIdx); i < end; i++) {
            if (LavaScan.dist(lvl, path.get(i), 1) <= 1) {
                msg(mc, "Lava muncul di jalur, hitung ulang.");
                releaseMove(mc);
                repath(mc);
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- scan & target

    private static void scan(Minecraft mc) {
        ClientLevel lvl = mc.level;
        AutoMineConfig cfg = AutoMineConfig.get();
        boolean wood = mode == Mode.WOOD;
        if (!wood) rebuildWanted();
        BlockPos c = mc.player.blockPosition();
        int r = cfg.scanRadius;
        long r2 = (long) r * r;
        int minY = Math.max(lvl.getMinY(), c.getY() - r);
        int maxY = Math.min(lvl.getMaxY(), c.getY() + r);
        List<Found> out = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int x = c.getX() - r; x <= c.getX() + r; x++) {
            for (int z = c.getZ() - r; z <= c.getZ() + r; z++) {
                m.set(x, c.getY(), z);
                if (!lvl.hasChunkAt(m)) continue;
                for (int y = minY; y <= maxY; y++) {
                    long dx = x - c.getX(), dy = y - c.getY(), dz = z - c.getZ();
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    m.set(x, y, z);
                    BlockState st = lvl.getBlockState(m);
                    if (st.isAir()) continue;
                    if (wood) {
                        if (st.is(BlockTags.LOGS)) out.add(new Found(m.immutable(), OreType.WOOD_COLOR));
                    } else {
                        Integer col = wanted.get(st.getBlock());
                        if (col != null) out.add(new Found(m.immutable(), col));
                    }
                }
            }
        }
        found = out;
    }

    private static boolean validTarget(ClientLevel lvl, BlockPos pos) {
        BlockState st = lvl.getBlockState(pos);
        return mode == Mode.WOOD ? st.is(BlockTags.LOGS) : wanted.containsKey(st.getBlock());
    }

    private static double score(BlockPos pos, BlockPos me) {
        double s = Vec3.atCenterOf(pos).distanceToSqr(Vec3.atCenterOf(me));
        int dy = pos.getY() - me.getY();
        if (mode == Mode.WOOD && dy > 1) s += dy * dy * 8.0; // utamakan batang bawah
        return s;
    }

    private static void pickTarget(Minecraft mc) {
        ClientLevel lvl = mc.level;
        AutoMineConfig cfg = AutoMineConfig.get();
        BlockPos me = mc.player.blockPosition();
        List<Found> cands = new ArrayList<>();
        for (Found f : found) if (!isBlacklisted(f.pos())) cands.add(f);
        cands.sort(Comparator.comparingDouble(f -> score(f.pos(), me)));

        int tries = 0;
        for (Found f : cands) {
            if (tries++ >= 6) break;
            BlockState st = lvl.getBlockState(f.pos());
            if (!canBreak(lvl, f.pos(), st)) { blacklist(f.pos(), 6000); continue; }
            if (mode == Mode.MINE && !hasCorrectTool(mc.player, st)) {
                blacklist(f.pos(), 600);
                if (ticks - lastToolMsg > 1200) {
                    lastToolMsg = ticks;
                    msg(mc, "Tidak ada tool yang cocok di hotbar untuk " + st.getBlock().getName().getString()
                            + " (butuh pickaxe yang sesuai), dilewati.");
                }
                continue;
            }
            List<BlockPos> np = findPath(lvl, me, f.pos());
            if (np != null) {
                target = f.pos(); path = np; pathIdx = 0; climbBase = null; climbing = false;
                stuck = offPath = repaths = 0; lastPos = null;
                return;
            }
            // kayu terlalu tinggi & tidak ada jalur: panjat dengan memasang blok ke bawah kaki
            if (mode == Mode.WOOD && cfg.pillar && placeableSlot(mc.player) >= 0) {
                BlockPos base = groundBelow(lvl, f.pos());
                if (base != null) {
                    List<BlockPos> bp = Pathfinder.find(lvl, me, base, 1.0, cfg.maxNodes, cfg.scanRadius + 10);
                    if (bp != null) {
                        target = f.pos(); path = bp; pathIdx = 0; climbBase = base; climbing = false;
                        stuck = offPath = repaths = 0; lastPos = null;
                        msg(mc, "Pohon tinggi: memanjat dengan blok.");
                        return;
                    }
                }
            }
            if (mode == Mode.MINE && cfg.tunneling) {
                target = f.pos();
                startDirect();
                msg(mc, "Jalur tidak ketemu: menggali langsung menuju ore.");
                return;
            }
            blacklist(f.pos(), 1200);
        }
    }

    /** Beralih ke mode gali-langsung: target tetap, jalur A* dibuang. */
    private static void startDirect() {
        path = List.of(); pathIdx = 0;
        directMode = true; directTicks = 0; directStuck = 0; directNext = null; directLast = null;
        directVisited.clear();
        stuck = offPath = repaths = 0; lastPos = null;
    }

    private static long lastToolMsg = -100000;

    /** Ada tool di hotbar yang menghasilkan drop dari blok ini (mis. debris butuh pickaxe diamond/netherite). */
    private static boolean hasCorrectTool(LocalPlayer p, BlockState st) {
        if (!st.requiresCorrectToolForDrops()) return true;
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.isCorrectToolForDrops(st) && !(isTool(s) && left(s) <= 1)) return true;
        }
        return false;
    }

    private static void blacklist(BlockPos p, int forTicks) {
        if (blacklist.size() > 2000) blacklist.values().removeIf(t -> t < ticks);
        blacklist.put(p.asLong(), ticks + forTicks);
    }

    private static boolean isBlacklisted(BlockPos p) {
        Long t = blacklist.get(p.asLong());
        return t != null && t > ticks;
    }

    // ---------------------------------------------------------------- bergerak

    private static void repath(Minecraft mc) {
        stuck = 0; offPath = 0;
        if (target == null) return;
        AutoMineConfig cfg = AutoMineConfig.get();
        boolean digFallback = mode == Mode.MINE && cfg.tunneling;
        if (++repaths > 3) {
            if (digFallback) { startDirect(); return; }
            blacklist(target, 1200);
            mc.gameMode.stopDestroyBlock();
            target = null; path = List.of(); pathIdx = 0;
            return;
        }
        List<BlockPos> np = findPath(mc.level, mc.player.blockPosition(), target);
        if (np == null) {
            if (digFallback) { startDirect(); return; }
            blacklist(target, 1200);
            target = null; path = List.of(); pathIdx = 0;
        } else {
            path = np; pathIdx = 0;
        }
    }

    private static void followPath(Minecraft mc) {
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        BlockPos cur = p.blockPosition();

        boolean on = false;
        for (int i = pathIdx; i < path.size(); i++) {
            if (path.get(i).equals(cur)) {
                if (i > pathIdx) repaths = 0;
                pathIdx = i; on = true;
            }
        }
        if (!on) { if (++offPath > 30) { repath(mc); return; } } else offPath = 0;

        // node terakhir tapi belum dalam jangkauan: jalan ke tengahnya
        if (pathIdx >= path.size() - 1) {
            BlockPos last = path.get(path.size() - 1);
            Vec3 c = new Vec3(last.getX() + 0.5, last.getY(), last.getZ() + 0.5);
            double hd = Math.hypot(p.getX() - c.x, p.getZ() - c.z);
            if (hd < 0.35 && p.onGround()) {
                releaseMove(mc);
                if (++stuck > 10) repath(mc);
                return;
            }
            walkToward(mc, c, false);
            return;
        }

        BlockPos next = path.get(pathIdx + 1);

        BlockPos blocker = firstBlocker(lvl, cur, next);
        if (blocker != null) {
            releaseMove(mc);
            stuck = 0; lastPos = null;
            if (!mineBlock(mc, blocker)) {
                mc.gameMode.stopDestroyBlock();
                if (mode == Mode.MINE && AutoMineConfig.get().tunneling && target != null) {
                    startDirect();
                } else {
                    if (target != null) blacklist(target, 1200);
                    target = null; path = List.of(); pathIdx = 0;
                }
            }
            return;
        }

        Vec3 nc = new Vec3(next.getX() + 0.5, next.getY(), next.getZ() + 0.5);
        if (cur.getX() == next.getX() && cur.getZ() == next.getZ() && cur.getY() > next.getY()) {
            // sedang di atas lubang: dorong ke tengah kolom lalu tunggu jatuh
            double hd = Math.hypot(p.getX() - nc.x, p.getZ() - nc.z);
            if (hd < 0.2) { releaseMove(mc); return; }
        }
        walkToward(mc, nc, next.getY() > cur.getY());

        Vec3 pos = p.position();
        if (lastPos != null && pos.distanceToSqr(lastPos) < 0.001) stuck++; else stuck = 0;
        lastPos = pos;
        if (stuck >= 10 && unstuckDig(mc, cur, next)) stuck = 10; // macet: hancurkan yang menghalangi
        if (stuck > 30) repath(mc);
    }

    private static BlockPos firstBlocker(ClientLevel lvl, BlockPos cur, BlockPos next) {
        List<BlockPos> cells = new ArrayList<>(4);
        if (next.getY() > cur.getY()) cells.add(cur.above(2));
        int top = Math.max(cur.getY(), next.getY()) + 1;
        for (int y = top; y >= next.getY(); y--) cells.add(new BlockPos(next.getX(), y, next.getZ()));
        for (BlockPos c : cells) {
            BlockState st = lvl.getBlockState(c);
            if (!st.getCollisionShape(lvl, c).isEmpty()) return c;
        }
        return null;
    }

    private static void walkToward(Minecraft mc, Vec3 t, boolean jumpHint) {
        LocalPlayer p = mc.player;
        double dx = t.x - p.getX(), dz = t.z - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        p.setYRot(yaw);
        p.setXRot(0f);
        mc.options.keyUp.setDown(true);
        boolean jump = (p.onGround() && (jumpHint || p.horizontalCollision || stepAhead(p, mc.level, t)))
                || p.isInWater();
        mc.options.keyJump.setDown(jump);
    }

    private static void releaseMove(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    // ---------------------------------------------------------------- menghancurkan

    private static void lookAt(LocalPlayer p, Vec3 t) {
        Vec3 e = p.getEyePosition();
        double dx = t.x - e.x, dy = t.y - e.y, dz = t.z - e.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        p.setYRot(yaw);
        p.setXRot(Mth.clamp(pitch, -90f, 90f));
    }

    /** Hancurkan blok yang menghalangi pandangan ke {@code pos} (atau pos itu sendiri). false = mustahil. */
    private static boolean mineBlock(Minecraft mc, BlockPos pos) {
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        Vec3 eye = p.getEyePosition();
        Vec3 c = Vec3.atCenterOf(pos);
        lookAt(p, c);

        BlockHitResult hit = lvl.clip(new ClipContext(eye, c, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        boolean isBlock = hit.getType() == HitResult.Type.BLOCK;
        BlockPos hp = isBlock ? hit.getBlockPos() : pos;
        Direction dir = isBlock ? hit.getDirection() : Direction.UP;

        BlockState hs = lvl.getBlockState(hp);
        if (hs.isAir()) return true;
        if (!hp.equals(pos) && !(allowDig(hs) && canBreak(lvl, hp, hs))) return false;
        if (hp.equals(pos) && !canBreak(lvl, hp, hs)) return false;

        if (AutoMineConfig.get().autoTool) selectTool(p, hs);
        mc.gameMode.continueDestroyBlock(hp, dir);
        p.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private static void selectTool(LocalPlayer p, BlockState st) {
        Inventory inv = p.getInventory();
        int bestSlot = inv.getSelectedSlot();
        float bestScore = toolScore(inv.getItem(bestSlot), st);
        for (int i = 0; i < 9; i++) {
            float s = toolScore(inv.getItem(i), st);
            if (s > bestScore) { bestScore = s; bestSlot = i; }
        }
        if (bestSlot != inv.getSelectedSlot()) inv.setSelectedSlot(bestSlot);
    }

    private static float toolScore(ItemStack s, BlockState st) {
        float sc = s.getDestroySpeed(st) + (s.isCorrectToolForDrops(st) ? 1000f : 0f);
        if (isTool(s) && left(s) <= 1) sc -= 5000f; // jangan pakai tool yang akan pecah
        return sc;
    }



    // ---------------------------------------------------------------- jalur: jalan dulu, kalau gagal gali terowongan

    /**
     * Mode MINE: coba jalur biasa (murah, mengutamakan jalan/gua), kalau gagal pakai mode TEROWONGAN
     * (menggali hampir lurus menuju ore, seperti penambang sungguhan).
     */
    private static List<BlockPos> findPath(ClientLevel lvl, BlockPos from, BlockPos to) {
        AutoMineConfig cfg = AutoMineConfig.get();
        boolean tun = mode == Mode.MINE && cfg.tunneling;
        List<BlockPos> np = Pathfinder.find(lvl, from, to, cfg.reach, tun ? 1500 : cfg.maxNodes, cfg.scanRadius + 10);
        if (np == null && tun) np = Pathfinder.find(lvl, from, to, cfg.reach, cfg.maxNodes * 3, cfg.scanRadius + 10, true);
        return np;
    }


    // ---------------------------------------------------------------- anti-macet & gali langsung

    /**
     * Pemain tidak bergerak padahal jalur menuju {@code next}: hancurkan blok apa pun yang menghalangi di
     * depan (kaki, kepala, dan di atas kepala kalau perlu naik). @return true kalau ada yang digali.
     */
    private static boolean unstuckDig(Minecraft mc, BlockPos cur, BlockPos next) {
        ClientLevel lvl = mc.level;
        int dx = Integer.signum(next.getX() - cur.getX());
        int dz = Integer.signum(next.getZ() - cur.getZ());
        int maxDy = next.getY() > cur.getY() ? 2 : 1;
        if (dx == 0 && dz == 0) {
            // turun / naik lurus: periksa blok di kolom sendiri
            BlockPos c = next.getY() > cur.getY() ? cur.above(2) : next;
            BlockState st = lvl.getBlockState(c);
            if (!st.getCollisionShape(lvl, c).isEmpty() && allowDig(st) && canBreak(lvl, c, st)) {
                releaseMove(mc);
                return mineBlock(mc, c);
            }
            return false;
        }
        for (int dy = 0; dy <= maxDy; dy++) {
            BlockPos c = cur.offset(dx, dy, dz);
            BlockState st = lvl.getBlockState(c);
            if (st.getCollisionShape(lvl, c).isEmpty()) continue;
            if (!allowDig(st) || !canBreak(lvl, c, st) || isDanger(st)) return false;
            releaseMove(mc);
            return mineBlock(mc, c);
        }
        return false;
    }

    /** Langkah berikut menuju target: tetangga aman yang paling mendekatkan, hindari sel yang baru dilewati. */
    private static BlockPos planDirect(ClientLevel lvl, BlockPos cur) {
        Vec3 tc = Vec3.atCenterOf(target);
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        int[] dys = {0, -1, 1};
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int dy : dys) {
                BlockPos n = cur.relative(d).offset(0, dy, 0);
                if (!stripOk(lvl, cur, n)) continue;
                double dist = new Vec3(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5).distanceToSqr(tc);
                if (directVisited.contains(n)) dist += 400.0;
                if (dist < bd) { bd = dist; best = n; }
            }
        }
        return best;
    }

    /**
     * Membuat jalan sendiri menuju target: tiap langkah gali semua blok yang menghalangi (2 blok atau lebih,
     * kaki + kepala), maju, ulangi sampai target masuk jangkauan. Dipakai saat A* gagal atau macet.
     */
    private static void directDigTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        if (target == null) { directMode = false; return; }
        if (++directTicks > 1500) {
            blacklist(target, 6000);
            mc.gameMode.stopDestroyBlock();
            target = null; directMode = false;
            return;
        }
        BlockPos cur = p.blockPosition();
        if (directNext == null || cur.equals(directNext) || cur.distSqr(directNext) > 9) {
            if (cur.equals(directNext)) {
                directVisited.addLast(cur);
                while (directVisited.size() > 16) directVisited.pollFirst();
            }
            directNext = planDirect(lvl, cur);
            directStuck = 0; directLast = null;
            if (directNext == null) {
                msg(mc, "Tidak ada jalan aman ke ore ini, dilewati.");
                blacklist(target, 6000);
                mc.gameMode.stopDestroyBlock();
                target = null; directMode = false;
                return;
            }
        }
        BlockPos blocker = firstBlocker(lvl, cur, directNext);
        if (blocker != null) {
            releaseMove(mc);
            directStuck = 0; directLast = null;
            if (!mineBlock(mc, blocker)) {
                mc.gameMode.stopDestroyBlock();
                directVisited.addLast(directNext);
                directNext = null;
            }
            return;
        }
        walkToward(mc, new Vec3(directNext.getX() + 0.5, directNext.getY(), directNext.getZ() + 0.5),
                directNext.getY() > cur.getY());
        Vec3 pos = p.position();
        if (directLast != null && pos.distanceToSqr(directLast) < 0.0004) directStuck++; else directStuck = 0;
        directLast = pos;
        if (directStuck >= 10 && unstuckDig(mc, cur, directNext)) directStuck = 10;
        if (directStuck > 40) { directVisited.addLast(directNext); directNext = null; directStuck = 0; }
    }

    // ---------------------------------------------------------------- memungut drop

    private static void startCollect(Minecraft mc, BlockPos pos) {
        LocalPlayer p = mc.player;
        Vec3 c = Vec3.atCenterOf(pos);
        if (p.position().distanceToSqr(c) < 2.0) return;
        List<BlockPos> cp = Pathfinder.find(mc.level, p.blockPosition(), pos, 1.0, 1500, 14);
        if (cp == null) return;
        collectPos = pos; collectTicks = 0;
        path = cp; pathIdx = 0; stuck = offPath = repaths = 0; lastPos = null;
    }

    /** Jalan ke bekas ore agar item drop terambil. @return true kalau tick dipakai. */
    private static boolean collectTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        Vec3 c = Vec3.atCenterOf(collectPos);
        if (++collectTicks > 100 || p.position().distanceToSqr(c) < 2.0) {
            collectPos = null; path = List.of(); pathIdx = 0;
            releaseMove(mc);
            return false;
        }
        if (path.isEmpty() || pathIdx >= path.size() - 1) walkToward(mc, c, collectPos.getY() > p.blockPosition().getY());
        else followPath(mc);
        return true;
    }

    // ---------------------------------------------------------------- strip mining (jelajah)

    private static Integer depthTarget() {
        AutoMineConfig cfg = AutoMineConfig.get();
        OreType[] pr = {OreType.DEBRIS, OreType.DIAMOND, OreType.EMERALD, OreType.REDSTONE, OreType.LAPIS,
                OreType.GOLD, OreType.IRON, OreType.COPPER, OreType.COAL, OreType.QUARTZ};
        for (OreType t : pr) if (cfg.isOreEnabled(t)) return t.bestY;
        return null;
    }

    private static boolean solidAt(ClientLevel lvl, BlockPos pos) {
        BlockState st = lvl.getBlockState(pos);
        return !st.getCollisionShape(lvl, pos).isEmpty() && st.getFluidState().isEmpty();
    }

    /** Langkah terowongan cur -> n aman? (tidak ada lava/air, blok keras/block entity, jatuh tinggi). */
    private static boolean stripOk(ClientLevel lvl, BlockPos cur, BlockPos n) {
        AutoMineConfig cfg = AutoMineConfig.get();
        if (n.getY() <= lvl.getMinY() + 1 || n.getY() >= lvl.getMaxY() - 3) return false;
        List<BlockPos> cells = new ArrayList<>(4);
        if (n.getY() > cur.getY()) {
            cells.add(cur.above(2));
            if (!solidAt(lvl, n.below())) return false; // naik tangga butuh pijakan
        }
        int top = Math.max(cur.getY(), n.getY()) + 1;
        for (int y = top; y >= n.getY(); y--) cells.add(new BlockPos(n.getX(), y, n.getZ()));
        for (BlockPos c : cells) {
            if (!lvl.hasChunkAt(c)) return false;
            BlockState st = lvl.getBlockState(c);
            if (isDanger(st) || !st.getFluidState().isEmpty()) return false;
            if (!st.getCollisionShape(lvl, c).isEmpty()) {
                if (!cfg.tunneling || !allowDig(st) || !canBreak(lvl, c, st)) return false;
            }
        }
        if (cfg.avoidLava && (LavaScan.dist(lvl, n, 2) <= 1 || LavaScan.dist(lvl, n.above(), 2) <= 1)) return false;
        BlockPos f = n;
        int fall = 0;
        while (!solidAt(lvl, f.below())) {
            f = f.below();
            BlockState fs = lvl.getBlockState(f);
            if (isDanger(fs) || !fs.getFluidState().isEmpty()) return false;
            if (++fall > 3) return false;
        }
        BlockState floor = lvl.getBlockState(f.below());
        return floor.getFluidState().isEmpty() && !isDanger(floor);
    }

    private static BlockPos planStrip(ClientLevel lvl, BlockPos cur) {
        AutoMineConfig cfg = AutoMineConfig.get();
        Integer wantY = cfg.autoDepth ? depthTarget() : null;
        Direction d = stripDir;
        for (int i = 0; i < 4; i++) {
            BlockPos fwd = cur.relative(d);
            if (wantY != null) {
                BlockPos v = null;
                if (cur.getY() > wantY + 1) v = fwd.below();        // tangga turun ke level ore
                else if (cur.getY() < wantY - 1) v = fwd.above();   // tangga naik
                if (v != null && stripOk(lvl, cur, v)) { stripDir = d; return v; }
            }
            if (stripOk(lvl, cur, fwd)) { stripDir = d; return fwd; }
            d = d.getClockWise();
        }
        return null;
    }

    /** Tidak ada ore terlihat: gali terowongan 1x2 ke depan (turun ke level terbaik dulu). */
    private static void stripTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        BlockPos cur = p.blockPosition();
        if (stripDir == null) stripDir = p.getDirection();
        if (stripNext == null || cur.equals(stripNext) || cur.distSqr(stripNext) > 9) {
            stripNext = planStrip(lvl, cur);
            stripStuck = 0; stripLast = null;
            if (stripNext == null) { stop("Tidak ada arah aman untuk menggali (lava/air/blok keras)"); return; }
        }
        BlockPos blocker = firstBlocker(lvl, cur, stripNext);
        if (blocker != null) {
            releaseMove(mc);
            stripStuck = 0; stripLast = null;
            if (!mineBlock(mc, blocker)) {
                mc.gameMode.stopDestroyBlock();
                stripNext = null; stripDir = stripDir.getClockWise();
            }
            return;
        }
        walkToward(mc, new Vec3(stripNext.getX() + 0.5, stripNext.getY(), stripNext.getZ() + 0.5),
                stripNext.getY() > cur.getY());
        Vec3 pos = p.position();
        if (stripLast != null && pos.distanceToSqr(stripLast) < 0.0004) stripStuck++; else stripStuck = 0;
        stripLast = pos;
        if (stripStuck > 40) { stripNext = null; stripDir = stripDir.getClockWise(); stripStuck = 0; }
    }

    // ---------------------------------------------------------------- durability & /repair

    private static boolean isTool(ItemStack s) {
        return !s.isEmpty() && s.isDamageableItem() && s.has(DataComponents.TOOL);
    }

    private static int left(ItemStack s) { return s.getMaxDamage() - s.getDamageValue(); }

    /**
     * Cek durability tool di hotbar. Kalau ada yang hampir habis: pegang tool itu lalu kirim /repair
     * (sama seperti mengetik di chat). @return true kalau tick ini dipakai.
     */
    private static boolean durabilityWatch(Minecraft mc) {
        AutoMineConfig cfg = AutoMineConfig.get();
        if (!cfg.autoRepair) return false;
        LocalPlayer p = mc.player;
        Inventory inv = p.getInventory();

        // verifikasi hasil /repair sebelumnya
        if (repairCheckAt > 0 && ticks >= repairCheckAt) {
            repairCheckAt = -1;
            ItemStack h = p.getMainHandItem();
            if (!h.isEmpty() && left(h) > repairBefore) { repairTries = 0; msg(mc, "Repair berhasil."); }
            else { repairTries++; msg(mc, "Repair belum berhasil (" + repairTries + "x), cooldown/izin server?"); }
        }

        int slot = -1, least = Integer.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (isTool(s) && left(s) <= cfg.minDurability && left(s) < least) { least = left(s); slot = i; }
        }
        if (slot < 0) { repairTries = 0; repairWait = 0; return false; }

        if (ticks - lastRepair < cfg.repairCooldown) {
            // masih cooldown: kalau tool nyaris pecah, diam dulu daripada merusaknya
            if (cfg.protectTool && least <= 1) {
                releaseMove(mc);
                mc.gameMode.stopDestroyBlock();
                return true;
            }
            return false;
        }
        if (cfg.protectTool && least <= 1 && repairTries >= 3) {
            stop("Tool hampir pecah & /" + cfg.repairCommand + " gagal 3x");
            return true;
        }

        releaseMove(mc);
        mc.gameMode.stopDestroyBlock();
        if (inv.getSelectedSlot() != slot) {          // pegang tool yang sekarat
            inv.setSelectedSlot(slot);
            repairWait = 3;                           // beri waktu paket slot sampai ke server
            return true;
        }
        if (repairWait > 0) { repairWait--; return true; }

        repairBefore = left(p.getMainHandItem());
        p.connection.sendCommand(cfg.repairCommand);  // = mengetik "/repair" di chat
        lastRepair = ticks;
        repairCheckAt = ticks + 40;
        msg(mc, "Durability tersisa " + least + " -> /" + cfg.repairCommand);
        return true;
    }

    // ---------------------------------------------------------------- loncat cerdas

    /** Ada blok setinggi 1 di depan dan ruang kepala bebas -> perlu loncat (seperti pemain). */
    private static boolean stepAhead(LocalPlayer p, ClientLevel lvl, Vec3 t) {
        double dx = t.x - p.getX(), dz = t.z - p.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 0.05) return false;
        double ax = p.getX() + dx / len * 0.7, az = p.getZ() + dz / len * 0.7;
        BlockPos feet = BlockPos.containing(ax, p.getY() + 0.05, az);
        if (lvl.getBlockState(feet).getCollisionShape(lvl, feet).isEmpty()) return false;
        BlockPos h1 = feet.above(), h2 = feet.above(2);
        return lvl.getBlockState(h1).getCollisionShape(lvl, h1).isEmpty()
                && lvl.getBlockState(h2).getCollisionShape(lvl, h2).isEmpty();
    }

    // ---------------------------------------------------------------- memanjat pohon tinggi

    private static void resetClimb() {
        climbBase = null; climbing = false; descending = false;
        pillarFloor = null; climbTicks = 0; descendWait = 0;
        placed.clear();
    }

    /** Slot hotbar berisi blok murah (dirt/cobble/stone/netherrack) untuk pilar; -1 kalau tidak ada. */
    private static int placeableSlot(LocalPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) continue;
            Block b = bi.getBlock();
            if (b instanceof EntityBlock || b instanceof FallingBlock) continue;
            BlockState st = b.defaultBlockState();
            if (st.is(BlockTags.DIRT) || st.is(BlockTags.BASE_STONE_OVERWORLD)
                    || b == Blocks.COBBLESTONE || b == Blocks.COBBLED_DEEPSLATE
                    || b == Blocks.NETHERRACK || b == Blocks.SANDSTONE) return i;
        }
        return -1;
    }

    /** Sel tempat berdiri di tanah tepat di bawah kolom {@code log} (batang & daun dilewati). */
    private static BlockPos groundBelow(ClientLevel lvl, BlockPos log) {
        BlockPos.MutableBlockPos m = log.mutable();
        for (int i = 0; i < 64; i++) {
            m.move(Direction.DOWN);
            if (!lvl.hasChunkAt(m)) return null;
            BlockState st = lvl.getBlockState(m);
            if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) continue;
            if (isDanger(st) || !st.getFluidState().isEmpty()) return null;
            if (!st.getCollisionShape(lvl, m).isEmpty()) {
                BlockPos stand = m.above().immutable();
                if (!lvl.getBlockState(stand).getCollisionShape(lvl, stand).isEmpty()) return null;
                return stand;
            }
        }
        return null;
    }

    private static void startClimb(Minecraft mc) {
        climbing = true; climbTicks = 0; pillarFloor = null;
        placed.clear();
        path = List.of(); pathIdx = 0;
        releaseMove(mc);
    }

    private static void endClimb(Minecraft mc) {
        climbing = false; climbBase = null; pillarFloor = null;
        descending = !placed.isEmpty();
        descendWait = 0;
        releaseMove(mc);
    }

    private static void abortClimb(Minecraft mc, String why) {
        msg(mc, "Panjat dibatalkan: " + why);
        if (target != null) blacklist(target, 3000);
        mc.gameMode.stopDestroyBlock();
        target = null; path = List.of(); pathIdx = 0;
        endClimb(mc);
    }

    /** Saat memanjat: setelah satu log habis, lanjut ke log berikutnya yang masih sekolom (tanpa turun dulu). */
    private static void pickClimbTarget(Minecraft mc) {
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        AutoMineConfig cfg = AutoMineConfig.get();
        Vec3 eye = p.getEyePosition();
        double lim = (cfg.reach + 6) * (cfg.reach + 6);
        double maxH = cfg.reach - 0.5;
        BlockPos best = null;
        double bd = lim;
        for (Found f : found) {
            BlockPos fp = f.pos();
            if (isBlacklisted(fp) || !validTarget(lvl, fp)) continue;
            if (Math.hypot(fp.getX() + 0.5 - p.getX(), fp.getZ() + 0.5 - p.getZ()) > maxH) continue;
            double d = Vec3.atCenterOf(fp).distanceToSqr(eye);
            if (d < bd) { bd = d; best = fp; }
        }
        if (best != null) { target = best; path = List.of(); pathIdx = 0; }
    }

    /** Lompat + pasang blok di bawah kaki (pillar jump) sampai target masuk jangkauan. */
    private static void climbTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        AutoMineConfig cfg = AutoMineConfig.get();

        if (++climbTicks > 900) { abortClimb(mc, "terlalu lama"); return; }
        int slot = placeableSlot(p);
        if (slot < 0) { abortClimb(mc, "tidak ada blok dirt/cobble di hotbar"); return; }

        // ruang kepala untuk lompat: gali daun/batang yang menghalangi
        BlockPos feet = p.blockPosition();
        for (int k = 2; k <= 3; k++) {
            BlockPos c = feet.above(k);
            BlockState cs = lvl.getBlockState(c);
            if (cs.getCollisionShape(lvl, c).isEmpty()) continue;
            if (isDanger(cs) || !allowDig(cs) || !canBreak(lvl, c, cs)) { abortClimb(mc, "terhalang blok di atas"); return; }
            releaseMove(mc);
            lookAt(p, Vec3.atCenterOf(c));
            if (cfg.autoTool) selectTool(p, cs);
            mc.gameMode.continueDestroyBlock(c, Direction.DOWN);
            p.swing(InteractionHand.MAIN_HAND);
            return;
        }

        mc.options.keyUp.setDown(false);
        p.setXRot(90f);
        if (p.onGround()) {
            pillarFloor = p.blockPosition().below();
            mc.options.keyJump.setDown(true);
            return;
        }
        mc.options.keyJump.setDown(false);

        // di udara & kaki sudah lebih tinggi dari puncak blok baru -> taruh blok di bawah kaki
        if (pillarFloor != null && p.getY() >= pillarFloor.getY() + 2.0) {
            Inventory inv = p.getInventory();
            if (inv.getSelectedSlot() != slot) inv.setSelectedSlot(slot);
            BlockPos cell = pillarFloor.above();
            if (lvl.getBlockState(cell).canBeReplaced()) {
                BlockHitResult hit = new BlockHitResult(
                        Vec3.atCenterOf(pillarFloor).add(0, 0.5, 0), Direction.UP, pillarFloor, false);
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                p.swing(InteractionHand.MAIN_HAND);
                if (!lvl.getBlockState(cell).canBeReplaced()) {
                    placed.addLast(cell);
                    pillarFloor = cell;
                }
            }
        }
    }

    /** Turun dengan menghancurkan blok pilar dari atas ke bawah. @return true kalau tick dipakai. */
    private static boolean descendTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        while (!placed.isEmpty() && lvl.getBlockState(placed.peekLast()).isAir()) placed.pollLast();
        if (placed.isEmpty()) { descending = false; return false; }

        BlockPos top = placed.peekLast();
        if (!p.blockPosition().below().equals(top)) {
            // sedang jatuh 1 blok / sudah pindah jauh dari pilar
            if (p.blockPosition().distSqr(top) > 16 || ++descendWait > 40) {
                placed.clear(); descending = false; return false;
            }
            releaseMove(mc);
            return true;
        }
        descendWait = 0;
        releaseMove(mc);
        p.setXRot(90f);
        BlockState st = lvl.getBlockState(top);
        if (AutoMineConfig.get().autoTool) selectTool(p, st);
        mc.gameMode.continueDestroyBlock(top, Direction.UP);
        p.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    // ---------------------------------------------------------------- aturan dunia

    static boolean isDanger(BlockState st) {
        Block b = st.getBlock();
        return b == Blocks.LAVA || b == Blocks.FIRE || b == Blocks.SOUL_FIRE
                || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE || b == Blocks.CACTUS
                || b == Blocks.MAGMA_BLOCK || b == Blocks.SWEET_BERRY_BUSH
                || b == Blocks.POWDER_SNOW || b == Blocks.COBWEB || b == Blocks.WITHER_ROSE;
    }

    /** Apakah blok penghalang (bukan target) boleh digali pada mode saat ini. */
    static boolean allowDig(BlockState st) {
        if (mode == Mode.WOOD) return st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES);
        return AutoMineConfig.get().tunneling;
    }

    /** Aman dihancurkan: bukan bedrock, bukan block entity (chest dll), tidak keras, tidak bersebelahan cairan. */
    static boolean canBreak(ClientLevel lvl, BlockPos pos, BlockState st) {
        float h = st.getDestroySpeed(lvl, pos);
        if (h < 0 || h > 35) return false; // 35: Ancient Debris (30) boleh, obsidian (50) tidak
        if (!st.getFluidState().isEmpty()) return false;
        if (st.hasBlockEntity()) return false;
        for (Direction d : Direction.values()) {
            if (!lvl.getFluidState(pos.relative(d)).isEmpty()) return false;
        }
        return true;
    }
}
