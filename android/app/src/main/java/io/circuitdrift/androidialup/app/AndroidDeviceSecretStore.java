package io.circuitdrift.androidialup.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Android Keystore-backed AES-GCM storage for the relay device credential. */
final class AndroidDeviceSecretStore {
    private static final String PREFS = "relay_settings";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "androidialup.device-secret.v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ENVELOPE_PREFIX = "v1:";

    private AndroidDeviceSecretStore() {}

    static DeviceSecretStore open(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new DeviceSecretStore(new PreferenceStore(prefs), new KeystoreCipher());
    }

    private static final class PreferenceStore implements DeviceSecretStore.Store {
        private final SharedPreferences prefs;

        PreferenceStore(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @Override public String get(String key) {
            return prefs.getString(key, null);
        }

        @Override public void put(String key, String value) {
            if (!prefs.edit().putString(key, value).commit()) {
                throw new IllegalStateException("failed to persist encrypted device secret");
            }
        }

        @Override public void remove(String key) {
            if (prefs.contains(key) && !prefs.edit().remove(key).commit()) {
                throw new IllegalStateException("failed to remove device secret preference");
            }
        }
    }

    private static final class KeystoreCipher implements DeviceSecretStore.Cipher {
        @Override
        public String encrypt(String plaintext) throws Exception {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
            String body = Base64.encodeToString(ciphertext, Base64.NO_WRAP);
            return ENVELOPE_PREFIX + iv + ":" + body;
        }

        @Override
        public String decrypt(String envelope) throws Exception {
            if (envelope == null || !envelope.startsWith(ENVELOPE_PREFIX)) {
                throw new IllegalArgumentException("unsupported device secret envelope");
            }
            int separator = envelope.indexOf(':', ENVELOPE_PREFIX.length());
            if (separator < 0) throw new IllegalArgumentException("malformed device secret envelope");
            byte[] iv = Base64.decode(envelope.substring(ENVELOPE_PREFIX.length(), separator), Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(envelope.substring(separator + 1), Base64.NO_WRAP);
            if (iv.length == 0 || ciphertext.length == 0) {
                throw new IllegalArgumentException("malformed device secret envelope");
            }

            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            java.security.Key key = keyStore.getKey(KEY_ALIAS, null);
            if (!(key instanceof SecretKey)) {
                throw new IllegalStateException("device secret key is unavailable");
            }

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, (SecretKey) key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        }

        private static SecretKey getOrCreateKey() throws Exception {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;

            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        }
    }
}
