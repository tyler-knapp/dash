package com.tylerknapp.finance.ledger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Ledger reads (SPEC §8 GET /api/transactions). Filter rules mirror the prototype's renderLedger. */
@Component
public class TransactionQueries {
    public static final int DEFAULT_LIMIT = 150; // the prototype's "Show more" step
    public static final int MAX_LIMIT = 500;
    /** SPEC §7: below this (and not reviewed) a transaction needs review. */
    public static final BigDecimal REVIEW_THRESHOLD = new BigDecimal("0.7");

    private final NamedParameterJdbcTemplate jdbc;

    public TransactionQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Filter(YearMonth from, YearMonth to, UUID account, String category, String q, boolean needsReview,
                         boolean includeTransfers, String cursor, int limit) {}

    public record TxnView(UUID id, LocalDate date, String merchant, String rawName, BigDecimal amount, UUID accountId,
                          String accountName, String accountColor, String category, String subcategory,
                          String categorySource, BigDecimal confidence, boolean reviewed, boolean pending,
                          boolean needsReview) {}

    public record Page(List<TxnView> transactions, String nextCursor, int total) {}

    private static final String SELECT = """
            select t.id, t.seq, t.date, coalesce(nullif(t.merchant_name, ''), t.raw_name, '') as merchant, t.raw_name,
                   t.amount, t.account_id, a.name as account_name, a.display_color, t.category, t.subcategory,
                   t.category_source, t.confidence, t.reviewed, t.pending
            from transaction t join account a on a.id = t.account_id
            """;

    /** A row plus its position, for building the next cursor. */
    record Positioned(TxnView view, long seq) {}

    static final RowMapper<Positioned> POSITIONED = (rs, i) -> new Positioned(TransactionQueries.MAPPER.mapRow(rs, i), rs.getLong("seq"));

    static final RowMapper<TxnView> MAPPER = (rs, i) -> {
        String category = rs.getString("category");
        BigDecimal conf = rs.getBigDecimal("confidence");
        boolean reviewed = rs.getBoolean("reviewed");
        return new TxnView(rs.getObject("id", UUID.class), rs.getObject("date", LocalDate.class),
                rs.getString("merchant"), rs.getString("raw_name"), rs.getBigDecimal("amount"),
                rs.getObject("account_id", UUID.class), rs.getString("account_name"), rs.getString("display_color"),
                category, rs.getString("subcategory"), rs.getString("category_source"), conf, reviewed,
                rs.getBoolean("pending"), needsReview(category, conf, reviewed));
    };

    /** Uncategorized, or a low-confidence guess nobody has confirmed yet. */
    static boolean needsReview(String category, BigDecimal confidence, boolean reviewed) {
        if (category == null) {
            return true;
        }
        return !reviewed && (confidence == null || confidence.compareTo(REVIEW_THRESHOLD) < 0);
    }

    public Page list(Filter f) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", f.from().atDay(1))
                .addValue("toExcl", f.to().plusMonths(1).atDay(1));
        StringBuilder where = new StringBuilder(" where not a.hidden and t.date >= :from and t.date < :toExcl");
        if (f.account() != null) {
            where.append(" and t.account_id = :account");
            p.addValue("account", f.account());
        }
        if (f.category() != null) {
            where.append(" and t.category = :category");
            p.addValue("category", f.category());
        }
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" and (coalesce(t.merchant_name, '') ilike :q escape '\\' or coalesce(t.raw_name, '') ilike :q escape '\\')");
            p.addValue("q", "%" + f.q().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        // Prototype: transfers are hidden unless "Show transfers" is on or the Transfer category is picked.
        if (!f.includeTransfers() && !"Transfer".equals(f.category())) {
            where.append(" and t.category is distinct from 'Transfer'");
        }
        if (f.needsReview()) {
            where.append(" and (t.category is null or (not t.reviewed and coalesce(t.confidence, 0) < :threshold))");
            p.addValue("threshold", REVIEW_THRESHOLD);
        }

        Integer total = jdbc.queryForObject("select count(*) from transaction t join account a on a.id = t.account_id"
                + where, p, Integer.class);

        StringBuilder sql = new StringBuilder(SELECT).append(where);
        Cursor c = Cursor.decode(f.cursor());
        if (c != null) {
            sql.append(" and (t.date < :cDate or (t.date = :cDate and t.seq > :cSeq))");
            p.addValue("cDate", c.date()).addValue("cSeq", c.seq());
        }
        int limit = Math.max(1, Math.min(f.limit(), MAX_LIMIT));
        sql.append(" order by t.date desc, t.seq limit :limit");
        p.addValue("limit", limit + 1);

        List<Positioned> rows = jdbc.query(sql.toString(), p, POSITIONED);
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            Positioned last = rows.get(rows.size() - 1);
            next = new Cursor(last.view().date(), last.seq()).encode();
        }
        return new Page(rows.stream().map(Positioned::view).toList(), next, total == null ? 0 : total);
    }

    public Optional<TxnView> find(UUID id) {
        return jdbc.query(SELECT + " where t.id = :id", new MapSqlParameterSource("id", id), MAPPER).stream().findFirst();
    }

    /** Opaque keyset cursor: the (date, seq) of the last row returned. */
    record Cursor(LocalDate date, long seq) {
        String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((date + "|" + seq).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String s) {
            if (s == null || s.isBlank()) {
                return null;
            }
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8).split("\\|");
                return new Cursor(LocalDate.parse(parts[0]), Long.parseLong(parts[1]));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid cursor");
            }
        }
    }
}
