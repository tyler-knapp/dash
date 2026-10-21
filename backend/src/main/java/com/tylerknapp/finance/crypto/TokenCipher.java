package com.tylerknapp.finance.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts Plaid access tokens at rest with AES-256-GCM.
 *
 * <p>Stored format: {@code [version=1][12-byte IV][ciphertext + 16-byte tag]}. A fixed
 * associated-data label binds ciphertexts to this use. The key comes from {@code TOKEN_ENC_KEY}
 * (32 random bytes, base64).
 */
public final class TokenCipher {
    private static final byte VERSION = 1;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD = "finance:plaid-access-token:v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    private TokenCipher(SecretKey key) {
        this.key = key;
    }

    /** @throws IllegalArgumentException if the key isn't base64 for exactly 32 bytes. */
    public static TokenCipher fromBase64Key(String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("TOKEN_ENC_KEY is not valid base64");
        }
        if (raw.length != 32) {
            throw new IllegalArgumentException(
                    "TOKEN_ENC_KEY must decode to 32 bytes (got " + raw.length + "). Generate one with: openssl rand -base64 32");
        }
        return new TokenCipher(new SecretKeySpec(raw, "AES"));
    }

    public byte[] encrypt(String plaintext) {
        byte[] iv = new byte[IV_LEN];
        random.nextBytes(iv);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(AAD);
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_LEN + ct.length).put(VERSION).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Token encryption failed", e);
        }
    }

    public String decrypt(byte[] stored) {
        if (stored == null || stored.length < 1 + IV_LEN + TAG_BITS / 8 || stored[0] != VERSION) {
            throw new IllegalStateException("Stored token has an unknown format");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 1, IV_LEN));
            c.updateAAD(AAD);
            byte[] pt = c.doFinal(stored, 1 + IV_LEN, stored.length - 1 - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Wrong key or tampered data. Never include the ciphertext or key in the message.
            throw new IllegalStateException("Could not decrypt stored token (wrong TOKEN_ENC_KEY?)");
        }
    }
}
