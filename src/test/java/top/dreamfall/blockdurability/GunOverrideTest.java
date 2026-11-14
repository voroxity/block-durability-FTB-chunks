package top.dreamfall.blockdurability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GunOverrideTest {

    @Test
    @DisplayName("Parsing: full entry")
    void parsesFullEntry() {
        var o = GunOverride.parse("tacz:ak47|damage=2.5|penetration=true").orElseThrow();
        assertEquals(2.5f, o.damage());
        assertTrue(o.penetration());
        assertTrue(o.matches("tacz:ak47"));
        assertFalse(o.matches("tacz:ak47_custom"));
    }

    @Test
    @DisplayName("Parsing: spaces around the pipes are still tolerated")
    void toleratesSpaces() {
        var o = GunOverride.parse("tacz:ak47 | damage=2.5 | penetration=true").orElseThrow();
        assertEquals(2.5f, o.damage());
        assertTrue(o.penetration());
        assertTrue(o.matches("tacz:ak47"));
    }

    @Test
    @DisplayName("Parsing: keys are optional and default to normal behavior")
    void keysAreOptional() {
        var damageOnly = GunOverride.parse("tacz:m700|damage=10").orElseThrow();
        assertEquals(10f, damageOnly.damage());
        assertFalse(damageOnly.penetration());

        var penOnly = GunOverride.parse("tacz:m4a1|penetration=true").orElseThrow();
        assertNull(penOnly.damage());
        assertTrue(penOnly.penetration());

        var bare = GunOverride.parse("tacz:glock").orElseThrow();
        assertNull(bare.damage());
        assertFalse(bare.penetration());

        assertEquals(0f, GunOverride.parse("tacz:x|damage=0").orElseThrow().damage());
        assertTrue(GunOverride.parse("tacz:x|penetration=FALSE").isPresent());
    }

    @Test
    @DisplayName("Parsing: 'pentation' spelling is accepted as penetration")
    void acceptsMisspelling() {
        assertTrue(GunOverride.parse("tacz:ak47|pentation=true").orElseThrow().penetration());
    }

    @Test
    @DisplayName("Parsing: invalid entries are rejected")
    void rejectsInvalid() {
        assertTrue(GunOverride.parse(null).isEmpty());
        assertTrue(GunOverride.parse("").isEmpty());
        assertTrue(GunOverride.parse("|damage=2").isEmpty());
        assertTrue(GunOverride.parse("tacz:ak47=2").isEmpty());           // old "gun=damage" format
        assertTrue(GunOverride.parse("tacz:ak47|damage=-1").isEmpty());
        assertTrue(GunOverride.parse("tacz:ak47|damage=NaN").isEmpty());
        assertTrue(GunOverride.parse("tacz:ak47|damage=abc").isEmpty());
        assertTrue(GunOverride.parse("tacz:ak47|penetration=maybe").isEmpty());
        assertTrue(GunOverride.parse("tacz:ak47|speed=3").isEmpty());
        assertTrue(GunOverride.parse("tacz:ak47|damage").isEmpty());
        assertFalse(GunOverride.isValid(42));
        assertFalse(GunOverride.isValid(null));
        assertTrue(GunOverride.isValid("tacz:ak47|damage=1|penetration=true"));
    }

    @Test
    @DisplayName("Lookup: wildcards, first match wins, left-out keys do not fall through")
    void lookup() {
        List<GunOverride> list = List.of(
                GunOverride.parse("tacz:ak47|penetration=true").orElseThrow(),
                GunOverride.parse("tacz:*|damage=0.5").orElseThrow());

        var ak = GunOverride.find(list, "tacz:ak47").orElseThrow();
        assertTrue(ak.penetration());
        assertNull(ak.damage());                                          // does NOT inherit the wildcard's 0.5

        var other = GunOverride.find(list, "tacz:m4a1").orElseThrow();
        assertEquals(0.5f, other.damage());
        assertFalse(other.penetration());

        assertTrue(GunOverride.find(list, "othermod:blaster").isEmpty());
        assertTrue(GunOverride.find(List.of(), "tacz:ak47").isEmpty());
    }
}
