package com.tylerknapp.finance.networth;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/networth (SPEC §8): the latest balance of every account, grouped like the prototype,
 * with totals. Net worth is the sum of each visible account's most recent snapshot; hidden accounts
 * and accounts with no balance yet are left out of the totals.
 */
@RestController
public class NetWorthController {
    private final JdbcTemplate jdbc;

    public NetWorthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AccountBalance(UUID id, String name, String institutionName, String kind, String mask, String displayColor,
                                 boolean isManual, boolean isSample, boolean linked, BigDecimal current, LocalDate asOf,
                                 String source) {}

    public record Group(String name, BigDecimal total, List<AccountBalance> accounts) {}

    public record NetWorth(BigDecimal total, BigDecimal cashMinusCards, LocalDate asOf, List<Group> groups,
                           List<AccountBalance> withoutBalance) {}

    private record Row(AccountBalance a, String group, int rank, java.time.OffsetDateTime created) {}

    @GetMapping("/api/networth")
    public NetWorth netWorth() {
        List<Row> rows = jdbc.query("""
                select a.id, a.name, i.institution_name, a.type, a.subtype, a.mask, a.display_color, a.is_manual, a.is_sample,
                       a.item_id is not null as linked, a.created_at, b.current, b.as_of, b.source
                from account a
                left join item i on i.id = a.item_id
                left join lateral (select current, as_of, source from balance_snapshot s
                                   where s.account_id = a.id order by as_of desc limit 1) b on true
                where not a.hidden""", (rs, i) -> {
            String kind = AccountKinds.kind(rs.getString("type"), rs.getString("subtype"));
            // Sample accounts stand in for linked ones unless the sample marks them manual.
            boolean linked = rs.getBoolean("linked") || (rs.getBoolean("is_sample") && !rs.getBoolean("is_manual"));
            AccountBalance a = new AccountBalance(rs.getObject("id", UUID.class), rs.getString("name"),
                    rs.getString("institution_name"), kind, rs.getString("mask"), rs.getString("display_color"),
                    rs.getBoolean("is_manual"), rs.getBoolean("is_sample"), linked, rs.getBigDecimal("current"),
                    rs.getObject("as_of", LocalDate.class), rs.getString("source"));
            return new Row(a, AccountKinds.group(rs.getString("type")), AccountKinds.kindRank(kind),
                    rs.getObject("created_at", java.time.OffsetDateTime.class));
        });
        rows.sort(Comparator.comparingInt((Row r) -> AccountKinds.GROUP_ORDER.indexOf(r.group()))
                .thenComparingInt(Row::rank).thenComparing(Row::created).thenComparing(r -> r.a().name()));

        Map<String, List<AccountBalance>> grouped = new LinkedHashMap<>();
        List<AccountBalance> without = new ArrayList<>();
        for (Row r : rows) {
            if (r.a().current() == null) {
                without.add(r.a());
            } else {
                grouped.computeIfAbsent(r.group(), k -> new ArrayList<>()).add(r.a());
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal retirement = BigDecimal.ZERO;
        List<Group> groups = new ArrayList<>();
        for (var e : grouped.entrySet()) {
            BigDecimal sum = e.getValue().stream().map(AccountBalance::current).reduce(BigDecimal.ZERO, BigDecimal::add);
            groups.add(new Group(e.getKey(), sum, e.getValue()));
            total = total.add(sum);
            if (AccountKinds.RETIREMENT.equals(e.getKey())) {
                retirement = retirement.add(sum);
            }
        }
        // "Balances as of": the newest balance fetched from a bank (manual entries carry their own dates).
        LocalDate asOf = rows.stream().map(Row::a).filter(a -> a.current() != null && !a.isManual())
                .map(AccountBalance::asOf).max(Comparator.naturalOrder())
                .orElse(rows.stream().map(Row::a).map(AccountBalance::asOf).filter(java.util.Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null));
        return new NetWorth(total, total.subtract(retirement), asOf, groups, without);
    }
}
