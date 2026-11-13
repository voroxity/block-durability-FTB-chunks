package top.dreamfall.blockdurability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class LandProfileTest {

    private static final LandProfile CLAIMED = new LandProfile(true, 2.0f, 0.5f, -1);

    @Test
    @DisplayName("Team override parsing: valid entries")
    void parsesValidEntries() {
        var o = LandProfile.TeamOverride.parse("Dragons|hpMultiplier=3.0|damageMultiplier=0.25|healTimeoutSeconds=300|enabled=true").orElseThrow();
        assertEquals("Dragons", o.selector());
        assertEquals(3.0f, o.hpMultiplier());
        assertEquals(0.25f, o.damageMultiplier());
        assertEquals(300, o.healTimeoutSeconds());
        assertTrue(o.enabled());

        var bare = LandProfile.TeamOverride.parse("Builders").orElseThrow();
        assertNull(bare.enabled());
        assertNull(bare.hpMultiplier());
    }

    @Test
    @DisplayName("Team override parsing: invalid entries are rejected")
    void rejectsInvalidEntries() {
        assertTrue(LandProfile.TeamOverride.parse("").isEmpty());
        assertTrue(LandProfile.TeamOverride.parse("   ").isEmpty());
        assertTrue(LandProfile.TeamOverride.parse("|hpMultiplier=2").isEmpty());
        assertTrue(LandProfile.TeamOverride.parse("T|hpMultiplier=0").isEmpty());
        assertTrue(LandProfile.TeamOverride.parse("T|hpMultiplier=-1").isEmpty());
        assertTrue(LandProfile.TeamOverride.parse("T|hpMultiplier=NaN").isEmpty());
        assertTrue(LandProfile.TeamOverride.parse("T|damageMultiplier=-0.1").isEmpty());
        assertTrue(LandProfile.TeamOverride.parse("T|damageMultiplier=Infinity").isEmpty());
        assertTrue(LandProfile.TeamOverride.parse("T|healTimeoutSeconds=-2").isEmpty());
        assertTrue(LandProfile.TeamOverride.parse("T|healTimeoutSeconds=1.5").isEmpty());
        assertTrue(LandProfile.TeamOverride.parse("T|enabled=maybe").isEmpty());
        assertTrue(LandProfile.TeamOverride.parse("T|unknownKey=1").isEmpty());
        assertTrue(LandProfile.TeamOverride.parse("T|hpMultiplier").isEmpty());
        assertFalse(LandProfile.TeamOverride.isValid(42));
        assertFalse(LandProfile.TeamOverride.isValid(null));
        assertTrue(LandProfile.TeamOverride.isValid("T|damageMultiplier=0"));
    }

    @Test
    @DisplayName("Team matching: UUID, short name, display name, case-insensitive")
    void matchesTeams() {
        UUID id = UUID.randomUUID();
        var byName = LandProfile.TeamOverride.parse("dragons").orElseThrow();
        assertTrue(byName.matches(id, "Dragons", "The Dragons"));
        assertTrue(byName.matches(id, "other", "DRAGONS"));
        assertFalse(byName.matches(id, "Wyverns", "The Wyverns"));

        var byId = LandProfile.TeamOverride.parse(id.toString().toUpperCase()).orElseThrow();
        assertTrue(byId.matches(id, "x", "y"));
        assertFalse(byId.matches(UUID.randomUUID(), "x", "y"));
        assertFalse(byName.matches(null, null, null));
    }

    @Test
    @DisplayName("Override replaces only the listed keys of the claimed profile")
    void overrideAppliesOnlyListedKeys() {
        var o = LandProfile.TeamOverride.parse("T|damageMultiplier=0.1").orElseThrow();
        LandProfile merged = o.applyTo(CLAIMED);
        assertEquals(new LandProfile(true, 2.0f, 0.1f, -1), merged);

        var off = LandProfile.TeamOverride.parse("T|enabled=false").orElseThrow().applyTo(CLAIMED);
        assertFalse(off.enabled());
        assertFalse(off.canDamage());
        assertEquals(2.0f, off.hpMultiplier());
    }

    @Test
    @DisplayName("canDamage: disabled or zero damage multiplier means invulnerable")
    void canDamage() {
        assertTrue(LandProfile.NEUTRAL.canDamage());
        assertFalse(new LandProfile(false, 1f, 1f, -1).canDamage());
        assertFalse(new LandProfile(true, 1f, 0f, -1).canDamage());
    }

    @Test
    @DisplayName("HP override parsing: valid and invalid entries")
    void parsesHpOverrides() {
        var ok = LandProfile.HpOverride.parse("minecraft:obsidian=300").orElseThrow();
        assertEquals(300f, ok.hp());
        assertTrue(ok.matches("minecraft:obsidian"));
        assertFalse(ok.matches("minecraft:obsidian_slab"));

        var wild = LandProfile.HpOverride.parse("minecraft:*glass*=2.5").orElseThrow();
        assertTrue(wild.matches("minecraft:glass"));
        assertTrue(wild.matches("minecraft:red_stained_glass_pane"));
        assertFalse(wild.matches("minecraft:stone"));

        assertTrue(LandProfile.HpOverride.parse(null).isEmpty());
        assertTrue(LandProfile.HpOverride.parse("minecraft:stone").isEmpty());
        assertTrue(LandProfile.HpOverride.parse("=5").isEmpty());
        assertTrue(LandProfile.HpOverride.parse("minecraft:stone=0").isEmpty());
        assertTrue(LandProfile.HpOverride.parse("minecraft:stone=-3").isEmpty());
        assertTrue(LandProfile.HpOverride.parse("minecraft:stone=NaN").isEmpty());
        assertTrue(LandProfile.HpOverride.parse("minecraft:stone=abc").isEmpty());
    }

    @Test
    @DisplayName("hpOverrideFor: first matching entry wins; empty when nothing matches")
    void hpOverrideLookup() {
        var profile = new LandProfile(true, 1f, 1f, -1, java.util.List.of(
                LandProfile.HpOverride.parse("minecraft:obsidian=300").orElseThrow(),
                LandProfile.HpOverride.parse("minecraft:*=5").orElseThrow()));
        assertEquals(300f, profile.hpOverrideFor("minecraft:obsidian").orElseThrow());
        assertEquals(5f, profile.hpOverrideFor("minecraft:dirt").orElseThrow());
        assertTrue(profile.hpOverrideFor("create:andesite_casing").isEmpty());
        assertTrue(LandProfile.NEUTRAL.hpOverrideFor("minecraft:obsidian").isEmpty());
    }

    @Test
    @DisplayName("Team overrides keep the claimed profile's HP overrides")
    void teamOverrideKeepsHpOverrides() {
        var claimed = new LandProfile(true, 2f, 1f, -1, java.util.List.of(
                LandProfile.HpOverride.parse("minecraft:obsidian=300").orElseThrow()));
        var merged = LandProfile.TeamOverride.parse("T|hpMultiplier=3.0").orElseThrow().applyTo(claimed);
        assertEquals(3f, merged.hpMultiplier());
        assertEquals(300f, merged.hpOverrideFor("minecraft:obsidian").orElseThrow());
    }
}
