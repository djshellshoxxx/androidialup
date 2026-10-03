package io.circuitdrift.androidialup.app;

import java.util.Objects;

/** Storage policy for the relay device credential.
 *
 * <p>The persistence and encryption mechanisms are injected so migration and no-plaintext
 * guarantees can be unit-tested without Android. Production uses {@link AndroidDeviceSecretStore}.
 */
final class DeviceSecretStore {
    static final String LEGACY_PLAINTEXT_KEY = "device_secret";
    static final String ENCRYPTED_KEY = "device_secret_encrypted_v1";

    interface Store {
        String get(String key);
        void put(String key, String value);
        void remove(String key);
    }

    interface Cipher {
        String encrypt(String plaintext) throws Exception;
        String decrypt(String ciphertext) throws Exception;
    }

    private final Store store;
    private final Cipher cipher;

    DeviceSecretStore(Store store, Cipher cipher) {
        this.store = Objects.requireNonNull(store, "store");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    String load() throws Exception {
        String encrypted = store.get(ENCRYPTED_KEY);
        if (encrypted != null && !encrypted.isEmpty()) {
            return cipher.decrypt(encrypted);
        }

        String legacy = store.get(LEGACY_PLAINTEXT_KEY);
        if (legacy == null || legacy.isEmpty()) {
            return "";
        }

        // Migration is transactional from the caller's perspective: encrypted data is written
        // successfully before the legacy plaintext key is removed.
        save(legacy);
        return legacy;
    }

    void save(String plaintext) throws Exception {
        String value = plaintext == null ? "" : plaintext.trim();
        if (value.isEmpty()) {
            store.remove(ENCRYPTED_KEY);
            store.remove(LEGACY_PLAINTEXT_KEY);
            return;
        }

        String encrypted = cipher.encrypt(value);
        if (encrypted == null || encrypted.isEmpty()) {
            throw new IllegalStateException("cipher returned an empty envelope");
        }
        store.put(ENCRYPTED_KEY, encrypted);
        store.remove(LEGACY_PLAINTEXT_KEY);
    }
}
