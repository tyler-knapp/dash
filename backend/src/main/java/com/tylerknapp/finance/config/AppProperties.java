package com.tylerknapp.finance.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * All app config, bound from env vars (see application.yml and .env.example).
 * Secrets are never printed: toString() reports only whether each one is set.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @NotNull Plaid plaid,
        String tokenEncKey,
        String anthropicApiKey,
        String passwordHash,
        @NotNull ZoneId timeZone,
        @Valid @NotNull Sync sync) {

    /**
     * @param schedulerEnabled run the periodic sync of all Items
     * @param cron             when (in {@code timeZone}); default every 6 hours
     * @param followUpDelays   after linking, re-sync at these delays until Plaid finishes pulling history
     */
    public record Sync(boolean schedulerEnabled, @NotNull String cron, @NotNull List<Duration> followUpDelays) {}

    public record Plaid(
            String clientId,
            String secret,
            @NotNull @Pattern(regexp = "sandbox|production", message = "PLAID_ENV must be sandbox or production")
            String env,
            String redirectUri) {

        public boolean configured() {
            return isSet(clientId) && isSet(secret);
        }

        @Override
        public String toString() {
            return "Plaid[env=" + env + ", clientId=" + mark(clientId) + ", secret=" + mark(secret)
                    + ", redirectUri=" + redirectUri + "]";
        }
    }

    public boolean tokenEncKeySet() {
        return isSet(tokenEncKey);
    }

    public boolean anthropicConfigured() {
        return isSet(anthropicApiKey);
    }

    public boolean authEnabled() {
        return isSet(passwordHash);
    }

    @Override
    public String toString() {
        return "AppProperties[" + plaid + ", tokenEncKey=" + mark(tokenEncKey)
                + ", anthropicApiKey=" + mark(anthropicApiKey) + ", passwordHash=" + mark(passwordHash)
                + ", timeZone=" + timeZone + ", " + sync + "]";
    }

    static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    private static String mark(String s) {
        return isSet(s) ? "<set>" : "<unset>";
    }
}
