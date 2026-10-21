package com.tylerknapp.finance.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TokenCipherTest {
    static String randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    final TokenCipher cipher = TokenCipher.fromBase64Key(randomKey());
    final String token = "access-sandbox-12345678-aaaa-bbbb-cccc-1234567890ab";

    @Test
    void roundTrips() {
        assertThat(cipher.decrypt(cipher.encrypt(token))).isEqualTo(token);
    }

    @Test
    void ciphertextHidesPlaintextAndUsesFreshIv() {
        byte[] a = cipher.encrypt(token);
        byte[] b = cipher.encrypt(token);
        assertThat(new String(a, StandardCharsets.ISO_8859_1)).doesNotContain("access-sandbox");
        assertThat(a).isNotEqualTo(b);
        assertThat(a[0]).isEqualTo((byte) 1);
    }

    @Test
    void tamperingIsDetected() {
        byte[] ct = cipher.encrypt(token);
        ct[ct.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(ct)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(token);
    }

    @Test
    void wrongKeyFails() {
        byte[] ct = cipher.encrypt(token);
        assertThatThrownBy(() -> TokenCipher.fromBase64Key(randomKey()).decrypt(ct))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBadKeys() {
        assertThatThrownBy(() -> TokenCipher.fromBase64Key("not base64!!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TokenCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessageContaining("32 bytes");
    }
}
