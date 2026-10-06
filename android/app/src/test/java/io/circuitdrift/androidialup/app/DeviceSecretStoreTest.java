package io.circuitdrift.androidialup.app;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public final class DeviceSecretStoreTest {
    @Test
    public void saveNeverLeavesPlaintextAndReloadsSecret() throws Exception {
        MemoryStore prefs = new MemoryStore();
        DeviceSecretStore store = new DeviceSecretStore(prefs, new PrefixCipher());

        store.save("a1b2c3");

        assertNull(prefs.get(DeviceSecretStore.LEGACY_PLAINTEXT_KEY));
        assertNotEquals("a1b2c3", prefs.get(DeviceSecretStore.ENCRYPTED_KEY));
        assertEquals("a1b2c3", store.load());
    }

    @Test
    public void loadMigratesLegacyPlaintextAndDeletesIt() throws Exception {
        MemoryStore prefs = new MemoryStore();
        prefs.put(DeviceSecretStore.LEGACY_PLAINTEXT_KEY, "deadbeef");
        DeviceSecretStore store = new DeviceSecretStore(prefs, new PrefixCipher());

        assertEquals("deadbeef", store.load());
        assertNull(prefs.get(DeviceSecretStore.LEGACY_PLAINTEXT_KEY));
        assertNotNull(prefs.get(DeviceSecretStore.ENCRYPTED_KEY));
        assertEquals("deadbeef", store.load());
    }

    @Test
    public void blankSaveClearsBothFormats() throws Exception {
        MemoryStore prefs = new MemoryStore();
        DeviceSecretStore store = new DeviceSecretStore(prefs, new PrefixCipher());
        store.save("abcd");

        store.save("");

        assertEquals("", store.load());
        assertNull(prefs.get(DeviceSecretStore.LEGACY_PLAINTEXT_KEY));
        assertNull(prefs.get(DeviceSecretStore.ENCRYPTED_KEY));
    }

    private static final class PrefixCipher implements DeviceSecretStore.Cipher {
        public String encrypt(String plaintext) { return "enc:" + plaintext; }
        public String decrypt(String ciphertext) {
            if (!ciphertext.startsWith("enc:")) throw new IllegalArgumentException("bad envelope");
            return ciphertext.substring(4);
        }
    }

    private static final class MemoryStore implements DeviceSecretStore.Store {
        private final Map<String, String> values = new HashMap<>();
        public String get(String key) { return values.get(key); }
        public void put(String key, String value) { values.put(key, value); }
        public void remove(String key) { values.remove(key); }
    }
}
