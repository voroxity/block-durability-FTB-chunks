package top.dreamfall.blockdurability;

import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import dev.ftb.mods.ftbteams.api.Team;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * The only class that touches FTB Chunks / FTB Teams types. It is only loaded when FTB Chunks is present
 * (see {@link BlockDurability}), so the rest of the mod runs fine without it.
 */
public final class FtbChunksCompat implements LandProfiles.ClaimProvider {

    private boolean warned = false;

    private FtbChunksCompat() {}

    public static void init() {
        LandProfiles.setClaimProvider(new FtbChunksCompat());
    }

    @Override
    @Nullable
    public LandProfiles.ClaimInfo getClaim(ServerLevel level, int chunkX, int chunkZ) {
        try {
            FTBChunksAPI.API api = FTBChunksAPI.api();
            if (!api.isManagerLoaded()) return null;

            ClaimedChunk chunk = api.getManager().getChunk(new ChunkDimPos(level.dimension(), chunkX, chunkZ));
            if (chunk == null) return null;

            Team team = chunk.getTeamData().getTeam();
            return new LandProfiles.ClaimInfo(team.getId(), team.getShortName(), team.getName().getString());
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                BlockDurability.LOGGER.warn("FTB Chunks claim lookup failed; treating land as unclaimed. {}", e.toString());
            }
            return null;
        }
    }
}
