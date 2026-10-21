package com.tylerknapp.finance.store;

import com.tylerknapp.finance.plaid.PlaidGateway;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    /** Same colors the prototype uses for accounts, handed out in order. */
    static final List<String> PALETTE = List.of("#1f6b5c", "#2b6cb0", "#b4413a", "#3d8b5f", "#6b5bb4",
            "#a0522d", "#4f9a86", "#8a7dd0", "#2f7d4f", "#b7791f");

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** SPEC §5: depository and credit are ledger accounts; investment/retirement (and anything else) balance-only. */
    public static String roleFor(String plaidType) {
        return "depository".equals(plaidType) || "credit".equals(plaidType) ? "ledger" : "balance_only";
    }

    /**
     * Insert a new Plaid account, or refresh Plaid-owned fields on an existing one. The user's
     * name, color, role and hidden flag are left alone after the first insert.
     */
    public UUID upsertFromPlaid(UUID itemId, PlaidGateway.Account a) {
        Integer n = jdbc.queryForObject("select count(*) from account", Integer.class);
        String color = PALETTE.get((n == null ? 0 : n) % PALETTE.size());
        return jdbc.queryForObject("""
                insert into account (item_id, plaid_account_id, name, official_name, mask, type, subtype, role,
                                     display_color, is_manual)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, false)
                on conflict (plaid_account_id) do update set
                    official_name = excluded.official_name, mask = excluded.mask,
                    type = excluded.type, subtype = excluded.subtype
                returning id""", UUID.class,
                itemId, a.plaidAccountId(), a.name() == null ? "Account" : a.name(), a.officialName(), a.mask(),
                a.type(), a.subtype(), roleFor(a.type()), color);
    }
}
