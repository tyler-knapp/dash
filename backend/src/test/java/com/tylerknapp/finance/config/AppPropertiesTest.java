package com.tylerknapp.finance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AppPropertiesTest {
    @Configuration
    @EnableConfigurationProperties(AppProperties.class)
    static class Cfg {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Cfg.class)
            .withPropertyValues("app.time-zone=America/New_York", "app.sync.scheduler-enabled=false",
                    "app.sync.cron=0 0 */6 * * *", "app.sync.follow-up-delays=15s, 1m");

    @Test
    void bindsAndNeverPrintsSecrets() {
        runner.withPropertyValues(
                        "app.plaid.env=sandbox",
                        "app.plaid.client-id=client-123",
                        "app.plaid.secret=super-secret-value",
                        "app.token-enc-key=enc-key-value",
                        "app.anthropic-api-key=sk-ant-xyz")
                .run(ctx -> {
                    AppProperties p = ctx.getBean(AppProperties.class);
                    assertThat(p.plaid().configured()).isTrue();
                    assertThat(p.anthropicConfigured()).isTrue();
                    assertThat(p.authEnabled()).isFalse();
                    assertThat(p.sync().followUpDelays()).containsExactly(Duration.ofSeconds(15), Duration.ofMinutes(1));
                    assertThat(p.toString())
                            .doesNotContain("client-123", "super-secret-value", "enc-key-value", "sk-ant-xyz")
                            .contains("env=sandbox");
                });
    }

    @Test
    void rejectsUnknownPlaidEnv() {
        runner.withPropertyValues("app.plaid.env=development")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void acceptsProduction() {
        runner.withPropertyValues("app.plaid.env=production")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
