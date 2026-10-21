package com.tylerknapp.finance;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Pins "now" to noon New York time on 2026-10-21, the date the prototype fixtures were recorded for. */
@TestConfiguration
public class FixedClockConfig {
    public static final Instant NOW = Instant.parse("2026-10-21T16:00:00Z");

    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
