package com.tylerknapp.finance.store;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BalanceRepository {
    private final JdbcTemplate jdbc;

    public BalanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One row per account per day; later fetches on the same day overwrite. Amounts in app sign. */
    public void upsert(UUID accountId, LocalDate asOf, BigDecimal current, BigDecimal available, String source) {
        jdbc.update("""
                insert into balance_snapshot (account_id, as_of, current, available, source)
                values (?, ?, ?, ?, ?)
                on conflict (account_id, as_of) do update set
                    current = excluded.current, available = excluded.available, source = excluded.source,
                    created_at = now()""", accountId, asOf, current, available, source);
    }
}
