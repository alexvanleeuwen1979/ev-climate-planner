package nl.axlproductions.kiaklimaatplanner;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class KiaSessionStore {
    private static final String ALIAS = "kia-connect-session";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static KeyStore store() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = store();
        SecretKey key = (SecretKey) store.getKey(ALIAS, null);
        if (key != null) return key;
        if (!create) throw new GeneralSecurityException("Session key absent");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build());
        return generator.generateKey();
    }

    static String read(Context context) throws Exception {
        String saved = context.getSharedPreferences("kia_session", Context.MODE_PRIVATE).getString("sealed", null);
        if (saved == null) throw new GeneralSecurityException("Session absent");
        String[] parts = saved.split(":", 2);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key(false),
            new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    static void save(Context context, String session) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        String sealed = Base64.encodeToString(cipher.doFinal(session.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!context.getSharedPreferences("kia_session", Context.MODE_PRIVATE).edit()
                .putString("sealed", iv + ":" + sealed).commit()) {
            throw new java.io.IOException("Could not store the session");
        }
    }

    static void clear(Context context) throws Exception {
        store().deleteEntry(ALIAS);
        context.getSharedPreferences("kia_session", Context.MODE_PRIVATE).edit().clear().commit();
    }
}

