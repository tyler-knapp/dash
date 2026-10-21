package com.tylerknapp.finance.web;

import java.math.BigDecimal;
import java.sql.Array;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read models for GET /api/items. Never exposes tokens or cursors. */
@Component
public class ItemQueries {
    private final JdbcTemplate jdbc;

    public ItemQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AccountView(UUID id, String name, String officialName, String mask, String type, String subtype,
                              String role, String displayColor, boolean hidden, boolean isManual,
                              BigDecimal currentBalance, LocalDate balanceAsOf) {}

    public record ItemView(UUID id, String institutionName, String status, OffsetDateTime lastSyncedAt,
                           List<String> products, String transactionsUpdateStatus, String lastErrorCode,
                           List<AccountView> accounts) {}

    public List<ItemView> items() {
        Map<UUID, ItemView> out = new LinkedHashMap<>();
        jdbc.query("""
                select id, institution_name, status, last_synced_at, products, transactions_update_status, last_error_code
                from item order by created_at""", rs -> {
            Array p = rs.getArray("products");
            UUID id = rs.getObject("id", UUID.class);
            out.put(id, new ItemView(id, rs.getString("institution_name"), rs.getString("status"),
                    rs.getObject("last_synced_at", OffsetDateTime.class),
                    p == null ? List.of() : Arrays.asList((String[]) p.getArray()),
                    rs.getString("transactions_update_status"), rs.getString("last_error_code"), new ArrayList<>()));
        });
        jdbc.query("""
                select a.*, b.current, b.as_of
                from account a
                left join lateral (select current, as_of from balance_snapshot s
                                   where s.account_id = a.id order by as_of desc limit 1) b on true
                where a.item_id is not null
                order by a.created_at, a.name""", rs -> {
            ItemView item = out.get(rs.getObject("item_id", UUID.class));
            if (item != null) {
                item.accounts().add(new AccountView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("official_name"), rs.getString("mask"), rs.getString("type"),
                        rs.getString("subtype"), rs.getString("role"), rs.getString("display_color"),
                        rs.getBoolean("hidden"), rs.getBoolean("is_manual"), rs.getBigDecimal("current"),
                        rs.getObject("as_of", LocalDate.class)));
            }
        });
        return new ArrayList<>(out.values());
    }

    public ItemView item(UUID id) {
        return items().stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    }
}
