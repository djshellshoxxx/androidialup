package io.circuitdrift.androidialup.modem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModemProfileTest {

    @Test
    void factoryProfileMatchesS1Section10() {
        ModemProfile profile = ModemProfile.factory();
        assertEquals(0, profile.sRegister(0));
        assertEquals(43, profile.sRegister(2));
        assertEquals(13, profile.sRegister(3));
        assertEquals(10, profile.sRegister(4));
        assertEquals(8, profile.sRegister(5));
        assertEquals(60, profile.sRegister(7));
        assertEquals(14, profile.sRegister(10));
        assertEquals(50, profile.sRegister(12));
        assertEquals(8, profile.sRegisters().size());
    }

    @Test
    void unsupportedRegistersAreNotFabricated() {
        ModemProfile profile = ModemProfile.factory();
        assertFalse(profile.hasSRegister(1));
        assertFalse(profile.hasSRegister(99));
        assertThrows(IllegalArgumentException.class, () -> profile.sRegister(99));
        assertThrows(IllegalArgumentException.class, () -> profile.setSRegister(99, 1));
    }

    @Test
    void sRegisterValuesAreBoundedToOneOctet() {
        ModemProfile profile = ModemProfile.factory();
        profile.setSRegister(12, 255);
        assertEquals(255, profile.sRegister(12));
        assertThrows(IllegalArgumentException.class, () -> profile.setSRegister(12, 256));
        assertThrows(IllegalArgumentException.class, () -> profile.setSRegister(12, -1));
        assertEquals(255, profile.sRegister(12));
    }

    @Test
    void restoreFactoryResetsEverything() {
        ModemProfile profile = ModemProfile.factory();
        profile.setEcho(false);
        profile.setQuiet(true);
        profile.setVerbose(false);
        profile.setMode(ModemMode.BYTE_RELAY);
        profile.setNetworkPolicy(NetworkPolicy.CELLULAR_ONLY);
        profile.setExtendedDiagnostics(true);
        profile.setSRegister(12, 1);
        profile.restoreFactory();
        assertTrue(profile.echo());
        assertFalse(profile.quiet());
        assertTrue(profile.verbose());
        assertEquals(ModemMode.AUTO, profile.mode());
        assertEquals(NetworkPolicy.AUTO, profile.networkPolicy());
        assertFalse(profile.extendedDiagnostics());
        assertEquals(50, profile.sRegister(12));
    }

    @Test
    void sRegistersViewIsReadOnly() {
        ModemProfile profile = ModemProfile.factory();
        assertThrows(UnsupportedOperationException.class, () -> profile.sRegisters().put(12, 1));
    }
}
