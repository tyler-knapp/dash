package com.tylerknapp.finance.health;

import com.tylerknapp.finance.config.AppProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcTemplate jdbc;
    private final AppProperties props;

    public HealthController(JdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Liveness plus a DB round trip. Reports which config is present, never the values. */
    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean dbOk;
        try {
            dbOk = Integer.valueOf(1).equals(jdbc.queryForObject("select 1", Integer.class));
        } catch (Exception e) {
            log.warn("Health check: database unreachable: {}", e.getMessage());
            dbOk = false;
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("plaid", props.plaid().configured());
        config.put("tokenEncKey", props.tokenEncKeySet());
        config.put("anthropic", props.anthropicConfigured());
        config.put("auth", props.authEnabled());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbOk ? "ok" : "degraded");
        body.put("db", dbOk ? "ok" : "down");
        body.put("plaidEnv", props.plaid().env());
        body.put("timeZone", props.timeZone().getId());
        body.put("config", config);
        return ResponseEntity.status(dbOk ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
