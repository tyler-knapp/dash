package com.tylerknapp.finance.crypto;

import com.tylerknapp.finance.config.AppProperties;
import com.tylerknapp.finance.config.NotConfiguredException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CryptoConfig {

    /** Fails startup on a malformed key; a missing key only fails when a token is actually needed. */
    @Bean
    public TokenCipherProvider tokenCipherProvider(AppProperties props) {
        TokenCipher cipher = props.tokenEncKeySet() ? TokenCipher.fromBase64Key(props.tokenEncKey()) : null;
        return () -> {
            if (cipher == null) {
                throw new NotConfiguredException("TOKEN_ENC_KEY is not set");
            }
            return cipher;
        };
    }

    @FunctionalInterface
    public interface TokenCipherProvider {
        TokenCipher get();
    }
}
