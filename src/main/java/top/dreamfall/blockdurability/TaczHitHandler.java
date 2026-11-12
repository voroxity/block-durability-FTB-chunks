package top.dreamfall.blockdurability;

import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.Optional;

/**
 * 监听 TaCZ 子弹命中事件（仅在 TaCZ 存在时注册）。
 */
public class TaczHitHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onAmmoHitBlock(AmmoHitBlockEvent event) {
        if (event.isCanceled()) return;
        if (!BlockHPConfig.ENABLED.get()) return;

        Level level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;

        BlockPos pos = event.getHitResult().getBlockPos();
        BlockState state = event.getState();

        // 不在白名单 → 什么都不做，TACZ 原逻辑照常
        if (!BlockHPManager.isBreakable(state)) return;

        // Claimed/unclaimed land rules (and per-team overrides): nothing to do where durability is off.
        LandProfile profile = LandProfiles.resolve(serverLevel, pos);
        if (!profile.canDamage()) return;

        EntityKineticBullet bullet = event.getAmmo();
        Vec3 hitVec = event.getHitResult().getLocation();

        // --- 计算单发直接命中伤害 ---
        float damage;
        if (BlockHPConfig.USE_BULLET_DAMAGE.get()) {
            damage = bullet.getDamage(hitVec)
                    * BlockHPConfig.BULLET_DAMAGE_MULTIPLIER.get().floatValue();
        } else {
            damage = BlockHPConfig.DAMAGE_PER_HIT.get().floatValue();
        }
        if (!Float.isFinite(damage) || damage <= 0) return;

        // --- 造成伤害 ---
        Optional<Float> remaining = BlockHPManager.damageBlock(serverLevel, pos, state, damage, profile);

        if (remaining.isEmpty()) {
            BlockHPEventHandler.spawnBreakEffects(serverLevel, pos, state);
        } else if (remaining.get() > 0) {
            float maxHP = BlockHPManager.getMaxHP(state, profile);
            float ratio = maxHP > 0 ? (1f - (remaining.get() / maxHP)) : 0f;
            BlockHPEventHandler.spawnHitEffects(serverLevel, pos, state, hitVec, ratio);
        }
    }
}
