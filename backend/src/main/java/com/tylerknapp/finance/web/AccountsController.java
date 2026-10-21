package com.tylerknapp.finance.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/accounts: every account with its latest balance. Phase 5 adds manual accounts and edits. */
@RestController
public class AccountsController {
    private final JdbcTemplate jdbc;

    public AccountsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AccountRow(UUID id, String name, String institutionName, String mask, String type, String subtype,
                             String role, String displayColor, boolean hidden, boolean isManual, boolean isSample,
                             BigDecimal currentBalance, LocalDate balanceAsOf) {}

    @GetMapping("/api/accounts")
    public List<AccountRow> accounts() {
        return jdbc.query("""
                select a.*, i.institution_name, b.current, b.as_of
                from account a
                left join item i on i.id = a.item_id
                left join lateral (select current, as_of from balance_snapshot s
                                   where s.account_id = a.id order by as_of desc limit 1) b on true
                order by a.is_sample, a.role, a.created_at, a.name""",
                (rs, i) -> new AccountRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("institution_name"), rs.getString("mask"), rs.getString("type"),
                        rs.getString("subtype"), rs.getString("role"), rs.getString("display_color"),
                        rs.getBoolean("hidden"), rs.getBoolean("is_manual"), rs.getBoolean("is_sample"),
                        rs.getBigDecimal("current"), rs.getObject("as_of", LocalDate.class)));
    }
}
