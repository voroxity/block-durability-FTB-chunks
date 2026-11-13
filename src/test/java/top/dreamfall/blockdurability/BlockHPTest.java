package top.dreamfall.blockdurability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

public class BlockHPTest {

    @Test
    @DisplayName("Wildcard glob to Pattern matching")
    void testGlobPatternMatching() {
        Pattern exactPattern = BlockHPManager.globToPattern("minecraft:stone");
        assertTrue(exactPattern.matcher("minecraft:stone").matches());
        assertFalse(exactPattern.matcher("minecraft:stone_stairs").matches());
        assertFalse(exactPattern.matcher("other:minecraft:stone").matches());

        Pattern wildcardPattern = BlockHPManager.globToPattern("minecraft:*glass*");
        assertTrue(wildcardPattern.matcher("minecraft:glass").matches());
        assertTrue(wildcardPattern.matcher("minecraft:stained_glass").matches());
        assertTrue(wildcardPattern.matcher("minecraft:glass_pane").matches());
        assertFalse(wildcardPattern.matcher("custom:stained_glass").matches());

        Pattern prefixWildcard = BlockHPManager.globToPattern("*glass");
        assertTrue(prefixWildcard.matcher("minecraft:glass").matches());
        assertTrue(prefixWildcard.matcher("tinted_glass").matches());
        assertFalse(prefixWildcard.matcher("glass_pane").matches());
    }

    @Test
    @DisplayName("Block list entry validation")
    void testBlockListEntryValidation() {
        assertTrue(BlockHPConfig.isValidBlockListEntry("tag:minecraft:planks"));
        assertTrue(BlockHPConfig.isValidBlockListEntry("tag:c:glass_blocks"));
        assertTrue(BlockHPConfig.isValidBlockListEntry("minecraft:stone"));
        assertTrue(BlockHPConfig.isValidBlockListEntry("minecraft:*glass*"));

        // Invalid entries must be rejected
        assertFalse(BlockHPConfig.isValidBlockListEntry(""));
        assertFalse(BlockHPConfig.isValidBlockListEntry("   "));
        assertFalse(BlockHPConfig.isValidBlockListEntry("tag:"));
        assertFalse(BlockHPConfig.isValidBlockListEntry("tag:invalid colon:spaced"));
        assertFalse(BlockHPConfig.isValidBlockListEntry(123));
    }

    @Test
    @DisplayName("HP override entry validation (guards against NaN / negative / infinite)")
    void testHpOverrideValidation() {
        assertTrue(BlockHPConfig.isValidHpOverride("minecraft:obsidian=20"));
        assertTrue(BlockHPConfig.isValidHpOverride("minecraft:*glass*=1.5"));
        assertTrue(BlockHPConfig.isValidHpOverride("tag:c:stone=10.0"));

        // Invalid numbers must be rejected
        assertFalse(BlockHPConfig.isValidHpOverride("minecraft:obsidian=-5"));
        assertFalse(BlockHPConfig.isValidHpOverride("minecraft:obsidian=0"));
        assertFalse(BlockHPConfig.isValidHpOverride("minecraft:obsidian=NaN"));
        assertFalse(BlockHPConfig.isValidHpOverride("minecraft:obsidian=Infinity"));
        assertFalse(BlockHPConfig.isValidHpOverride("minecraft:obsidian=-Infinity"));

        // Invalid formats must be rejected
        assertFalse(BlockHPConfig.isValidHpOverride("minecraft:obsidian"));
        assertFalse(BlockHPConfig.isValidHpOverride("=20"));
        assertFalse(BlockHPConfig.isValidHpOverride("minecraft:obsidian="));
        assertFalse(BlockHPConfig.isValidHpOverride("minecraft:obsidian=abc"));
        assertFalse(BlockHPConfig.isValidHpOverride(""));
    }

    @Test
    @DisplayName("Explosion linear falloff and damage calculation")
    void testExplosionDamageCalculation() {
        float radius = 4.0f;
        float baseDamage = radius * 4.0f; // 16.0f

        // Center: distance 0, falloff 1.0
        float centerFalloff = (float) Math.max(0.0, 1.0 - (0.0 / radius));
        assertEquals(1.0f, centerFalloff, 0.001f);
        assertEquals(16.0f, baseDamage * centerFalloff, 0.001f);

        // Halfway: distance 2.0, falloff 0.5
        float halfFalloff = (float) Math.max(0.0, 1.0 - (2.0 / radius));
        assertEquals(0.5f, halfFalloff, 0.001f);
        assertEquals(8.0f, baseDamage * halfFalloff, 0.001f);

        // Near the edge: distance 3.8, falloff 0.05
        float edgeFalloff = (float) Math.max(0.0, 1.0 - (3.8 / radius));
        assertEquals(0.05f, edgeFalloff, 0.001f);
        assertEquals(0.8f, baseDamage * edgeFalloff, 0.001f);

        // Outside the radius: distance 4.5, falloff 0.0
        float outFalloff = (float) Math.max(0.0, 1.0 - (4.5 / radius));
        assertEquals(0.0f, outFalloff, 0.001f);
    }
}
