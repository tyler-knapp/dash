package com.tylerknapp.finance.config;

/** A feature was used whose env config (Plaid keys, TOKEN_ENC_KEY, ...) isn't set. Maps to 503. */
public class NotConfiguredException extends RuntimeException {
    public NotConfiguredException(String message) {
        super(message);
    }
}
