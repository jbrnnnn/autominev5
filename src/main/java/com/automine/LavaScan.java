package com.automine;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

/** Deteksi lava di sekitar sebuah posisi (lava sumber maupun yang mengalir). */
final class LavaScan {
    private LavaScan() {}

    static boolean isLava(BlockState st) {
        return st.getFluidState().is(FluidTags.LAVA);
    }

    /**
     * Jarak Chebyshev (dalam blok, termasuk diagonal) ke lava terdekat di dalam kubus radius {@code max}.
     * 0 = {@code pos} sendiri lava; {@code max + 1} = tidak ada lava dalam radius.
     */
    static int dist(ClientLevel lvl, BlockPos pos, int max) {
        int best = max + 1;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                for (int dy = -max; dy <= max; dy++) {
                    int d = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
                    if (d >= best) continue;
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (!lvl.hasChunkAt(m)) continue;
                    if (isLava(lvl.getBlockState(m))) best = d;
                }
            }
        }
        return best;
    }
}
