package com.tylerknapp.finance;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the full app against a throwaway Postgres 16, the same major version as docker compose.
 * One container for the whole test run (started once, stopped by Testcontainers at JVM exit),
 * so Spring's cached context never points at a stopped database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Test-only key: base64 of the 32 ASCII bytes "test-only-key-not-for-production". Never a real key.
        "app.token-enc-key=dGVzdC1vbmx5LWtleS1ub3QtZm9yLXByb2R1Y3Rpb24=",
        "app.sync.scheduler-enabled=false",
        "app.sync.follow-up-delays="
})
public abstract class PostgresTestBase {
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
