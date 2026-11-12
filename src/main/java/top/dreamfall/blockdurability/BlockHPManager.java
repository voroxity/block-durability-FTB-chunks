package top.dreamfall.blockdurability;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Block durability management. Whitelist-based (tags supported) with HP computed from hardness.
 * Isolates state per dimension and provides timeout-based auto-healing and memory cleanup.
 */
public class BlockHPManager {

    public record BlockKey(ResourceKey<Level> dimension, BlockPos pos) {}

    private record DamageState(float currentHP, float maxHP, int crackId, long lastHitTime) {}

    private record HPOverride(Pattern pattern, float hp) {}

    private static final ConcurrentHashMap<BlockKey, DamageState> damagedBlocks = new ConcurrentHashMap<>();
    private static int nextCrackId = 0;

    /** Precompiled: tag whitelist */
    private static final List<TagKey<Block>> tagWhitelist = new ArrayList<>();
    /** Precompiled: name whitelist regex patterns */
    private static final List<Pattern> nameWhitelistPatterns = new ArrayList<>();
    /** Precompiled HP overrides: Pattern → hp */
    private static final List<HPOverride> hpOverrides = new ArrayList<>();
    private static volatile boolean compiled = false;

    // ==================== Public API ====================

    /**
     * Resets the precompiled caches (called on config hot reload)
     */
    public static synchronized void invalidateCache() {
        compiled = false;
        LandProfiles.invalidate();
        BlockDurability.LOGGER.info("Block Durability cache invalidated due to config reload.");
    }

    /**
     * @return whether the block is whitelisted (can be damaged)
     */
    public static boolean isBreakable(BlockState state) {
        compileIfNeeded();

        // Tag match (O(1), checked first)
        for (TagKey<Block> tag : tagWhitelist) {
            if (state.is(tag)) return true;
        }

        // Name match (regex & wildcard)
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        for (Pattern pattern : nameWhitelistPatterns) {
            if (pattern.matcher(id).matches()) return true;
        }
        return false;
    }

    /**
     * Damages a block. Blocks that are not whitelisted are ignored.
     * The land profile (claimed / unclaimed / team override) is applied automatically based on the block's position.
     * @return Optional.empty() = the block was destroyed; otherwise the remaining HP.
     */
    public static Optional<Float> damageBlock(Level level, BlockPos pos, BlockState state, float damage) {
        if (!(level instanceof ServerLevel serverLevel)) return Optional.of(0f);
        if (!isBreakable(state)) return Optional.of(0f);
        return damageBlock(serverLevel, pos, state, damage, LandProfiles.resolve(serverLevel, pos));
    }

    /**
     * Same as above, with an already-resolved land profile (lets explosion loops reuse a per-chunk lookup).
     * The profile's damageMultiplier is applied here, so callers pass the raw damage.
     */
    public static Optional<Float> damageBlock(ServerLevel serverLevel, BlockPos pos, BlockState state,
                                              float damage, LandProfile profile) {
        if (!isBreakable(state)) return Optional.of(0f);
        if (!profile.canDamage()) return Optional.of(0f);
        if (!Float.isFinite(damage) || damage <= 0) return Optional.of(0f);
        damage *= profile.damageMultiplier();
        if (!Float.isFinite(damage) || damage <= 0) return Optional.of(0f);

        BlockKey key = new BlockKey(serverLevel.dimension(), pos.immutable());
        DamageState existing = damagedBlocks.get(key);
        float maxHP = getMaxHP(state, profile);
        float currentHP;

        if (existing != null) {
            // Max HP changed (config edit, or the chunk was claimed/unclaimed/changed owner): start fresh.
            if (Math.abs(existing.maxHP - maxHP) > 0.01f) {
                clearCrackOverlay(serverLevel, pos, existing.crackId);
                damagedBlocks.remove(key);
                existing = null;
            }
        }

        currentHP = (existing != null) ? existing.currentHP : maxHP;
        float newHP = currentHP - damage;

        if (newHP <= 0) {
            damagedBlocks.remove(key);
            if (existing != null) clearCrackOverlay(serverLevel, pos, existing.crackId);
            serverLevel.destroyBlock(pos, BlockHPConfig.DROP_ITEMS.get());
            return Optional.empty();
        }

        int crackId = (existing != null) ? existing.crackId : allocateCrackId();
        damagedBlocks.put(key, new DamageState(newHP, maxHP, crackId, System.currentTimeMillis()));
        sendCrackProgress(serverLevel, pos, crackId, newHP, maxHP);
        return Optional.of(newHP);
    }

    /**
     * Gets a block's max HP: an override if one matches, otherwise the hardness formula.
     */
    public static float getMaxHP(BlockState state) {
        compileIfNeeded();
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

        for (HPOverride override : hpOverrides) {
            if (override.pattern().matcher(id).matches()) {
                return override.hp();
            }
        }

        float hardness = state.getBlock().defaultDestroyTime();
        float multiplier = BlockHPConfig.HARDNESS_MULTIPLIER.get().floatValue();
        float minHP = BlockHPConfig.MIN_BLOCK_HP.get().floatValue();
        float computed = hardness * multiplier;
        if (!Float.isFinite(computed) || computed < minHP) {
            return minHP;
        }
        return computed;
    }

    /**
     * Max HP on a specific piece of land: the base value scaled by the land profile's hpMultiplier.
     */
    public static float getMaxHP(BlockState state, LandProfile profile) {
        float scaled = getMaxHP(state) * profile.hpMultiplier();
        return Float.isFinite(scaled) ? Math.max(0.01f, scaled) : getMaxHP(state);
    }

    public static float getCurrentHP(Level level, BlockPos pos, BlockState state) {
        DamageState existing = damagedBlocks.get(new BlockKey(level.dimension(), pos));
        if (existing != null) return existing.currentHP;
        if (level instanceof ServerLevel serverLevel) {
            return getMaxHP(state, LandProfiles.resolve(serverLevel, pos));
        }
        return getMaxHP(state);
    }

    public static void onBlockRemoved(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            clearBlock(serverLevel, pos);
        } else {
            damagedBlocks.remove(new BlockKey(level.dimension(), pos));
        }
    }

    /**
     * Clears the damage state at a position and tells nearby players to remove the crack overlay.
     */
    public static void clearBlock(ServerLevel level, BlockPos pos) {
        BlockKey key = new BlockKey(level.dimension(), pos.immutable());
        DamageState removed = damagedBlocks.remove(key);
        if (removed != null) {
            clearCrackOverlay(level, pos, removed.crackId());
        }
    }

    public static void clearAll() {
        damagedBlocks.clear();
    }

    /**
     * Periodic healing and memory cleanup: blocks not damaged within the timeout lose their cracks and are dropped from the cache.
     * The timeout can be set via the config file or /gamerule blockDurabilityHealSeconds.
     */
    public static void tick(ServerLevel level) {
        int timeoutSeconds = BlockHPConfig.HEAL_TIMEOUT_SECONDS.get();
        if (level.getGameRules() != null) {
            int ruleVal = level.getGameRules().getInt(BlockDurability.RULE_BLOCK_HEAL_SECONDS);
            if (ruleVal != 120) {
                timeoutSeconds = ruleVal;
            }
        }

        final int globalTimeoutSec = timeoutSeconds;
        long now = System.currentTimeMillis();
        LandProfiles.Cache profiles = LandProfiles.newCache(level);

        damagedBlocks.entrySet().removeIf(entry -> {
            if (!entry.getKey().dimension().equals(level.dimension())) return false;
            BlockPos p = entry.getKey().pos();
            if (!level.isLoaded(p)) return false;

            // Block was destroyed (now air) or is no longer breakable → clear client cracks and drop it from the cache immediately
            BlockState current = level.getBlockState(p);
            if (current.isAir() || !isBreakable(current)) {
                clearCrackOverlay(level, p, entry.getValue().crackId());
                return true;
            }

            // The land is now one where durability is off (e.g. it was claimed by a team with enabled=false).
            LandProfile profile = profiles.get(p);
            if (!profile.enabled()) {
                clearCrackOverlay(level, p, entry.getValue().crackId());
                return true;
            }

            // Per-land heal timeout wins; -1 falls back to the gamerule/global setting.
            int timeoutSec = profile.healTimeoutSeconds() >= 0 ? profile.healTimeoutSeconds() : globalTimeoutSec;
            DamageState ds = entry.getValue();
            if (timeoutSec > 0 && now - ds.lastHitTime() > timeoutSec * 1000L) {
                clearCrackOverlay(level, p, ds.crackId());
                return true;
            }
            return false;
        });
    }

    /**
     * Clears the crack cache for a chunk when it unloads, to prevent memory leaks.
     */
    public static void onChunkUnload(ServerLevel level, ChunkPos chunkPos) {
        damagedBlocks.entrySet().removeIf(entry -> {
            if (!entry.getKey().dimension().equals(level.dimension())) return false;
            BlockPos p = entry.getKey().pos();
            if ((p.getX() >> 4) == chunkPos.x && (p.getZ() >> 4) == chunkPos.z) {
                clearCrackOverlay(level, p, entry.getValue().crackId());
                return true;
            }
            return false;
        });
    }

    // ==================== Internal ====================

    private static synchronized int allocateCrackId() {
        return nextCrackId++;
    }

    private static void sendCrackProgress(ServerLevel level, BlockPos pos, int crackId,
                                           float currentHP, float maxHP) {
        if (maxHP <= 0f) return;
        float ratio = 1f - (currentHP / maxHP);
        int progress = Math.clamp((int) (ratio * 10), 0, 9);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) < 1024) {
                player.connection.send(
                        new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                                crackId, pos, progress));
            }
        }
    }

    private static void clearCrackOverlay(ServerLevel level, BlockPos pos, int crackId) {
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) < 1024) {
                player.connection.send(
                        new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                                crackId, pos, -1));
            }
        }
    }

    private static synchronized void compileIfNeeded() {
        if (compiled) return;

        tagWhitelist.clear();
        nameWhitelistPatterns.clear();

        for (String entry : BlockHPConfig.BLOCK_WHITELIST.get()) {
            if (entry == null) continue;
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("tag:")) {
                // Tag match: tag:namespace:path
                String tagId = trimmed.substring(4).trim();
                ResourceLocation rl = tagId.isEmpty() ? null : ResourceLocation.tryParse(tagId);
                if (rl != null && !rl.getPath().isEmpty()) {
                    tagWhitelist.add(TagKey.create(Registries.BLOCK, rl));
                } else {
                    BlockDurability.LOGGER.warn("Invalid tag in block whitelist: '{}'. Skipping.", trimmed);
                }
            } else {
                // Name match (exact / wildcard)
                nameWhitelistPatterns.add(globToPattern(trimmed));
            }
        }

        // Compile HP overrides
        hpOverrides.clear();
        for (String entry : BlockHPConfig.BLOCK_HP_OVERRIDES.get()) {
            if (entry == null) continue;
            String[] parts = entry.split("=", 2);
            if (parts.length != 2) {
                BlockDurability.LOGGER.warn("Invalid format in HP override: '{}' (expected 'modid:block=hp'). Skipping.", entry);
                continue;
            }
            String key = parts[0].trim();
            if (key.isEmpty()) {
                BlockDurability.LOGGER.warn("Empty pattern in HP override: '{}'. Skipping.", entry);
                continue;
            }
            try {
                float hp = Float.parseFloat(parts[1].trim());
                if (!Float.isFinite(hp) || hp <= 0f) {
                    BlockDurability.LOGGER.warn("Invalid HP value in override: '{}' (must be a finite positive number). Skipping.", entry);
                    continue;
                }
                hpOverrides.add(new HPOverride(globToPattern(key), hp));
            } catch (NumberFormatException e) {
                BlockDurability.LOGGER.warn("Failed to parse HP number in override: '{}'. Skipping.", entry);
            }
        }

        compiled = true;
    }

    /**
     * Converts a wildcard glob into a regex Pattern
     */
    public static Pattern globToPattern(String glob) {
        String regex = "^" + Pattern.quote(glob).replace("*", "\\E.*\\Q") + "$";
        return Pattern.compile(regex);
    }
}
