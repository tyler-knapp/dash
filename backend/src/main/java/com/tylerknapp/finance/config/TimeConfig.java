package com.tylerknapp.finance.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class TimeConfig {
    /** Injected so tests can pin "today". Period math converts to app.time-zone where needed. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
