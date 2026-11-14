package top.dreamfall.blockdurability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GunDamageOverrideTest {

    @Test
    @DisplayName("Parsing: valid entries, including 0 (gun never damages blocks)")
    void parsesValid() {
        var ak = GunDamageOverride.parse("tacz:ak47=2.5").orElseThrow();
        assertEquals(2.5f, ak.damage());
        assertTrue(ak.matches("tacz:ak47"));
        assertFalse(ak.matches("tacz:ak47_custom"));

        assertEquals(0f, GunDamageOverride.parse("tacz:m700=0").orElseThrow().damage());
        assertTrue(GunDamageOverride.parse("  tacz:m4a1  =  3  ").isPresent());
    }

    @Test
    @DisplayName("Parsing: invalid entries are rejected")
    void rejectsInvalid() {
        assertTrue(GunDamageOverride.parse(null).isEmpty());
        assertTrue(GunDamageOverride.parse("tacz:ak47").isEmpty());
        assertTrue(GunDamageOverride.parse("=2").isEmpty());
        assertTrue(GunDamageOverride.parse("tacz:ak47=-1").isEmpty());
        assertTrue(GunDamageOverride.parse("tacz:ak47=NaN").isEmpty());
        assertTrue(GunDamageOverride.parse("tacz:ak47=Infinity").isEmpty());
        assertTrue(GunDamageOverride.parse("tacz:ak47=abc").isEmpty());
        assertFalse(GunDamageOverride.isValid(42));
        assertFalse(GunDamageOverride.isValid(null));
        assertTrue(GunDamageOverride.isValid("tacz:ak47=1"));
    }

    @Test
    @DisplayName("Wildcards and first-match-wins lookup")
    void lookup() {
        List<GunDamageOverride> list = List.of(
                GunDamageOverride.parse("tacz:ak47=5").orElseThrow(),
                GunDamageOverride.parse("tacz:*=0.5").orElseThrow());
        assertEquals(5f, GunDamageOverride.find(list, "tacz:ak47").orElseThrow());
        assertEquals(0.5f, GunDamageOverride.find(list, "tacz:m4a1").orElseThrow());
        assertTrue(GunDamageOverride.find(list, "othermod:blaster").isEmpty());
        assertTrue(GunDamageOverride.find(List.of(), "tacz:ak47").isEmpty());
    }
}
