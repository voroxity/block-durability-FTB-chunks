package top.dreamfall.blockdurability;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Core event handler: listens for explosions (all mods + TaCZ), healing and chunk lifecycle.
 * Depends on no TaCZ-specific classes, so it runs fine without TaCZ.
 */
public class BlockHPEventHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!BlockHPConfig.ENABLED.get() || !BlockHPConfig.EXPLOSION_ENABLED.get()) return;

        Level level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;

        Explosion explosion = event.getExplosion();
        Entity directSource = explosion.getDirectSourceEntity();

        // Detect whether this is a TaCZ explosion
        boolean isTacz = isTaczExplosion(directSource, explosion);
        if (!isTacz && !BlockHPConfig.HANDLE_VANILLA_EXPLOSIONS.get()) {
            return;
        }

        Vec3 center = explosion.center();
        float radius = explosion.radius();
        float maxRadius = BlockHPConfig.EXPLOSION_MAX_RADIUS.get().floatValue();
        float effectiveRadius = Math.min(radius, maxRadius);
        if (effectiveRadius <= 0f) return;

        // Blocks vanilla already selected for destruction (dual-track: blocks vanilla would destroy keep vanilla behavior)
        List<BlockPos> affectedBlocks = event.getAffectedBlocks();
        Set<BlockPos> vanillaDestroyed = new HashSet<>(affectedBlocks);

        int minX = (int) Math.floor(center.x - effectiveRadius);
        int maxX = (int) Math.ceil(center.x + effectiveRadius);
        int minY = Math.max(serverLevel.getMinBuildHeight(), (int) Math.floor(center.y - effectiveRadius));
        int maxY = Math.min(serverLevel.getMaxBuildHeight(), (int) Math.ceil(center.y + effectiveRadius));
        int minZ = (int) Math.floor(center.z - effectiveRadius);
        int maxZ = (int) Math.ceil(center.z + effectiveRadius);

        float multiplier = BlockHPConfig.EXPLOSION_DAMAGE_MULTIPLIER.get().floatValue();
        float baseDamage = effectiveRadius * 4.0f * multiplier;

        // Claims are per chunk, so memoise the land profile per chunk for this blast.
        LandProfiles.Cache landProfiles = LandProfiles.newCache(serverLevel);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    // Already destroyed by vanilla; no need to also deduct durability
                    if (vanillaDestroyed.contains(cursor)) continue;

                    double dx = (x + 0.5) - center.x;
                    double dy = (y + 0.5) - center.y;
                    double dz = (z + 0.5) - center.z;
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > effectiveRadius * effectiveRadius) continue;

                    BlockState state = serverLevel.getBlockState(cursor);
                    if (state.isAir()) continue;
                    if (!BlockHPManager.isBreakable(state)) continue;

                    // Claimed/unclaimed land rules: skip early (before the raycast) where durability can't apply.
                    LandProfile profile = landProfiles.get(cursor);
                    if (!profile.canDamage()) continue;

                    // Line-of-sight check (a solid, intact block in between shields the target from the blast)
                    Vec3 targetCenter = new Vec3(x + 0.5, y + 0.5, z + 0.5);
                    BlockHitResult hit = serverLevel.clip(new ClipContext(
                            center,
                            targetCenter,
                            ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE,
                            CollisionContext.empty()
                    ));
                    if (hit.getType() != HitResult.Type.MISS
                            && !hit.getBlockPos().equals(cursor)
                            && !vanillaDestroyed.contains(hit.getBlockPos())) {
                        continue;
                    }

                    double dist = Math.sqrt(distSq);
                    // Linear distance falloff
                    float falloff = (float) Math.max(0.0f, 1.0f - (dist / effectiveRadius));
                    float damage = baseDamage * falloff;
                    if (!Float.isFinite(damage) || damage <= 0.1f) continue;

                    BlockPos immutablePos = cursor.immutable();
                    Optional<Float> remaining = BlockHPManager.damageBlock(serverLevel, immutablePos, state, damage, profile);
                    if (remaining.isEmpty()) {
                        spawnBreakEffects(serverLevel, immutablePos, state);
                    } else if (remaining.get() > 0) {
                        float maxHP = BlockHPManager.getMaxHP(state, profile);
                        float ratio = maxHP > 0 ? (1f - (remaining.get() / maxHP)) : 0f;
                        spawnHitEffects(serverLevel, immutablePos, state, targetCenter, ratio);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            // Every 2 seconds (40 ticks), check damaged blocks for healing and memory cleanup
            if (serverLevel.getGameTime() % 40 == 0) {
                BlockHPManager.tick(serverLevel);
            }
        }
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            BlockHPManager.onChunkUnload(serverLevel, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            BlockHPManager.clearBlock(serverLevel, event.getPos());
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            BlockHPManager.clearBlock(serverLevel, event.getPos());
        }
    }

    public static void spawnBreakEffects(ServerLevel level, BlockPos pos, BlockState state) {
        if (BlockHPConfig.SPAWN_BREAK_PARTICLES.get()) {
            level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, state),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    20, 0.25, 0.25, 0.25, 0.5);
        }
        if (BlockHPConfig.PLAY_BREAK_SOUND.get()) {
            level.playSound(null, pos,
                    state.getSoundType().getBreakSound(),
                    SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    public static void spawnHitEffects(ServerLevel level, BlockPos pos, BlockState state,
                                       Vec3 hitVec, float damageRatio) {
        if (BlockHPConfig.SPAWN_BREAK_PARTICLES.get()) {
            int count = 1 + (int) (damageRatio * 8);
            level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, state),
                    hitVec.x, hitVec.y, hitVec.z,
                    count, 0.1, 0.1, 0.1, 0.2);
        }
        if (BlockHPConfig.PLAY_BREAK_SOUND.get()) {
            level.playSound(null, pos,
                    state.getSoundType().getHitSound(),
                    SoundSource.BLOCKS,
                    0.5F + damageRatio * 0.5F,
                    0.8F + level.random.nextFloat() * 0.4F);
        }
    }

    private static boolean isTaczExplosion(Entity directSource, Explosion explosion) {
        if (directSource != null && directSource.getClass().getName().contains("com.tacz")) {
            return true;
        }
        return explosion != null && explosion.getClass().getName().contains("com.tacz");
    }
}
