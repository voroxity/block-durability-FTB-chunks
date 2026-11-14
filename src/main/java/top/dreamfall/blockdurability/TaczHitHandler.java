package top.dreamfall.blockdurability;

import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.util.block.BlockRayTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Listens for TaCZ bullet-hit events (only registered when TaCZ is present).
 */
public class TaczHitHandler {

    /** Safety cap on how many blocks one bullet can punch through in a single tick. */
    private static final int MAX_PENETRATED_BLOCKS = 32;

    /** Nudge (in blocks) past the previous hit point so the next raycast starts inside the destroyed block. */
    private static final double RAY_NUDGE = 0.001;

    /**
     * Damage a penetrating bullet still carries after flying past the end of the tick's path.
     * Weak keys: entries disappear with the bullet entity.
     */
    private static final Map<EntityKineticBullet, Float> carriedDamage = new WeakHashMap<>();

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onAmmoHitBlock(AmmoHitBlockEvent event) {
        if (event.isCanceled()) return;
        if (!BlockHPConfig.ENABLED.get()) return;

        Level level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;

        BlockPos pos = event.getHitResult().getBlockPos();
        BlockState state = event.getState();

        // Not damageable (per the block list) → do nothing; TACZ's own logic proceeds as usual
        if (!BlockHPManager.isBreakable(state)) return;

        // Claimed/unclaimed land rules (and per-team overrides): nothing to do where durability is off.
        LandProfile profile = LandProfiles.resolve(serverLevel, pos);
        if (!profile.canDamage()) return;

        EntityKineticBullet bullet = event.getAmmo();
        Vec3 hitVec = event.getHitResult().getLocation();

        Optional<GunOverride> gunOverride = BlockHPManager.getGunOverride(bullet.getGunId().toString());
        boolean penetrates = gunOverride.isPresent() && gunOverride.get().penetration();

        // --- Compute the damage of a single direct hit ---
        float damage;
        Float carried = carriedDamage.remove(bullet);
        if (carried != null) {
            // This bullet already punched through earlier blocks; it only has the leftover damage.
            damage = carried;
        } else if (gunOverride.isPresent() && gunOverride.get().damage() != null) {
            // Exact per-gun value from the config; bulletDamageMultiplier etc. do not apply to it.
            damage = gunOverride.get().damage();
        } else if (BlockHPConfig.USE_BULLET_DAMAGE.get()) {
            damage = bullet.getDamage(hitVec)
                    * BlockHPConfig.BULLET_DAMAGE_MULTIPLIER.get().floatValue();
        } else {
            damage = BlockHPConfig.DAMAGE_PER_HIT.get().floatValue();
        }
        if (!Float.isFinite(damage) || damage <= 0) return;

        // --- Plain hit: the first block absorbs the bullet ---
        Vec3 motion = bullet.getDeltaMovement();
        if (!penetrates || motion.lengthSqr() < 1.0E-8) {
            BlockHPManager.DamageOutcome outcome = BlockHPManager.applyDamage(serverLevel, pos, state, damage, profile);
            spawnEffects(serverLevel, pos, state, hitVec, profile, outcome);
            return;
        }

        // --- Penetrating hit: leftover damage carries on to the next block along the bullet's path ---
        // During this event the bullet is still at the start of its tick's movement.
        Vec3 dir = motion.normalize();
        Vec3 endVec = bullet.position().add(motion);

        BlockPos curPos = pos;
        BlockState curState = state;
        LandProfile curProfile = profile;
        Vec3 curHit = hitVec;
        float remaining = damage;

        for (int i = 0; i < MAX_PENETRATED_BLOCKS; i++) {
            BlockHPManager.DamageOutcome outcome =
                    BlockHPManager.applyDamage(serverLevel, curPos, curState, remaining, curProfile);
            spawnEffects(serverLevel, curPos, curState, curHit, curProfile, outcome);

            // Survived, or destroyed with nothing left over: the bullet stops here (TaCZ discards it as usual).
            if (!outcome.destroyed() || outcome.leftoverDamage() <= 0f) return;
            remaining = outcome.leftoverDamage();

            // Find the next block the bullet would have hit this tick (TaCZ's own raycast: honors bullet_ignore).
            BlockHitResult next = BlockRayTrace.rayTraceBlocks(serverLevel,
                    new ClipContext(curHit.add(dir.scale(RAY_NUDGE)), endVec,
                            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bullet));

            if (next.getType() == HitResult.Type.MISS) {
                // Damage is left but nothing else is in this tick's path: keep the bullet flying (cancel TaCZ's
                // hit handling, which would discard it) and carry the leftover into the next hit.
                carriedDamage.put(bullet, remaining);
                event.setCanceled(true);
                return;
            }

            curPos = next.getBlockPos();
            curHit = next.getLocation();
            curState = serverLevel.getBlockState(curPos);

            // A block that cannot be damaged stops the bullet.
            if (!BlockHPManager.isBreakable(curState)) return;
            curProfile = LandProfiles.resolve(serverLevel, curPos);
            if (!curProfile.canDamage()) return;
        }
        // Reached the safety cap: the bullet stops.
    }

    private static void spawnEffects(ServerLevel level, BlockPos pos, BlockState state, Vec3 hitVec,
                                     LandProfile profile, BlockHPManager.DamageOutcome outcome) {
        if (outcome.destroyed()) {
            BlockHPEventHandler.spawnBreakEffects(level, pos, state);
        } else if (outcome.remainingHP() > 0) {
            float maxHP = BlockHPManager.getMaxHP(state, profile);
            float ratio = maxHP > 0 ? (1f - (outcome.remainingHP() / maxHP)) : 0f;
            BlockHPEventHandler.spawnHitEffects(level, pos, state, hitVec, ratio);
        }
    }
}
