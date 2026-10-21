package com.tylerknapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class HealthEndpointTest extends PostgresTestBase {
    @Autowired
    TestRestTemplate http;

    @Test
    @SuppressWarnings("unchecked")
    void healthReportsDbOkAndNoSecretValues() {
        ResponseEntity<Map> res = http.getForEntity("/api/health", Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).containsEntry("status", "ok").containsEntry("db", "ok")
                .containsEntry("plaidEnv", "sandbox").containsEntry("timeZone", "America/New_York");
        // Config section is booleans only.
        assertThat((Map<String, Object>) body.get("config")).allSatisfy((k, v) -> assertThat(v).isInstanceOf(Boolean.class));
    }
}
