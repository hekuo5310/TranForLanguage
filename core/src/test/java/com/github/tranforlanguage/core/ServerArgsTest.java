package com.github.tranforlanguage.core;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ServerArgsTest {
    @Test public void booleanAndDecimalDefaultsAreValidated() {
        ServerArgs defaults = new ServerArgs("probe", Arrays.asList("player"));
        assertEquals(1.5, defaults.decimal(1, "volume", 1.5), 0);
        assertTrue(defaults.bool(1, "value", true));
        assertFalse(defaults.bool(1, "value", false));

        ServerArgs values = new ServerArgs("probe", Arrays.asList("player", "TRUE", "0.25", "false"));
        assertTrue(values.bool(1, "value"));
        assertFalse(values.bool(3, "value"));
        assertEquals(0.25, values.decimal(2, "volume"), 0);
        try { values.bool(2, "value"); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("true or false")); }
    }

    @Test public void blockAndNumbersUseVanillaRounding() {
        ServerArgs values = new ServerArgs("probe", Arrays.asList("10.7", "-2.1", "nope"));
        assertEquals(10, values.block(0, "x"));
        assertEquals(-3, values.block(1, "y"));
        try { values.decimal(2, "y"); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("number")); }
    }
}
