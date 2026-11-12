package ru.boostyatv.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SessionStore {
    private static final String KEY = "boosty-session";
    private final SharedPreferences preferences;

    SessionStore(Context context) {
        this(context.getSharedPreferences("account", Context.MODE_PRIVATE));
    }

    SessionStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    String deviceId() {
        String id = preferences.getString("device", "");
        if (!id.isEmpty()) return id;
        id = UUID.randomUUID().toString();
        preferences.edit().putString("device", id).apply();
        return id;
    }

    JSONObject read() {
        String stored = preferences.getString("session", "");
        if (stored.isEmpty()) return null;
        try {
            ByteBuffer bytes = ByteBuffer.wrap(Base64.decode(stored, Base64.NO_WRAP));
            byte[] iv = new byte[12];
            bytes.get(iv);
            byte[] encrypted = new byte[bytes.remaining()];
            bytes.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
        } catch (Exception e) {
            clear();
            return null;
        }
    }

    void save(JSONObject session) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(session.toString().getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] combined = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
        if (!preferences.edit().putString("session", Base64.encodeToString(combined, Base64.NO_WRAP)).commit()) {
            throw new BoostyApi.Problem("Не удалось сохранить сессию на устройстве.");
        }
    }

    void clear() {
        preferences.edit().remove("session").apply();
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY)) return (SecretKey) store.getKey(KEY, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return generator.generateKey();
    }
}
