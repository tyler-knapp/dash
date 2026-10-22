package com.tylerknapp.finance.budget;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BudgetRepository {
    private static final TypeReference<Map<String, BigDecimal>> AMOUNTS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public BudgetRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public BudgetHistory load() {
        return new BudgetHistory(jdbc.query("select effective_from, amounts from budget_version", (rs, i) ->
                new BudgetHistory.Version(YearMonth.parse(rs.getString(1)), parse(rs.getString(2)))));
    }

    /** Insert or replace the version for its month. A version the user edits is theirs, not sample data. */
    public void save(BudgetHistory.Version v) {
        jdbc.update("""
                insert into budget_version (effective_from, amounts, is_sample) values (?, ?::jsonb, false)
                on conflict (effective_from) do update set amounts = excluded.amounts, is_sample = false""",
                v.effectiveFrom().toString(), write(v.amounts()));
    }

    /** Serialize edits: one budget write at a time (transaction-scoped lock). */
    public void lock() {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext('budget_version'))", Object.class);
    }

    private Map<String, BigDecimal> parse(String s) {
        try {
            return json.readValue(s, AMOUNTS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed budget_version.amounts", e);
        }
    }

    private String write(Map<String, BigDecimal> m) {
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
