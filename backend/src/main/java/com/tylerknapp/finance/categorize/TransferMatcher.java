package com.tylerknapp.finance.categorize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Pairs a new transaction with an equal, opposite amount on another of Tyler's own ledger
 * accounts within 3 days (SPEC §7 step 2): one leaves checking, the other lands in savings or on
 * a card. Both sides become Transfer. The other side may be new in the same sync or already in
 * the database (then it is updated too, unless Tyler set its category himself).
 *
 * <p>Guard against coincidences (a $40 dinner and a $40 refund): a side that Plaid confidently
 * puts in a non-transfer category doesn't pair, and amounts under $1 never pair.
 */
@Component
public class TransferMatcher {
    static final int WINDOW_DAYS = 3;
    static final BigDecimal MIN_AMOUNT = BigDecimal.ONE;
    static final BigDecimal PAIR_CONFIDENCE = new BigDecimal("0.9");

    private final JdbcTemplate jdbc;

    public TransferMatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A transaction that may pair. {@code accountRole} is ledger / balance_only. */
    public record Side(String key, UUID accountId, String accountType, String accountRole, LocalDate date,
                       BigDecimal amount, String pfcPrimary, String pfcConfidence) {}

    /** @return Transfer subcategory per candidate key that was paired */
    public Map<String, String> pair(List<Side> candidates) {
        Map<String, String> out = new HashMap<>();
        Set<String> used = new HashSet<>();
        List<Side> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing(Side::date).thenComparing(Side::key));
        for (Side c : sorted) {
            if (used.contains(c.key()) || !eligible(c)) {
                continue;
            }
            // Same sync first.
            Side best = null;
            for (Side o : sorted) {
                if (o == c || used.contains(o.key()) || !eligible(o) || o.accountId().equals(c.accountId())
                        || o.amount().compareTo(c.amount().negate()) != 0
                        || Math.abs(ChronoUnit.DAYS.between(c.date(), o.date())) > WINDOW_DAYS) {
                    continue;
                }
                if (best == null || Math.abs(ChronoUnit.DAYS.between(c.date(), o.date()))
                        < Math.abs(ChronoUnit.DAYS.between(c.date(), best.date()))) {
                    best = o;
                }
            }
            if (best != null) {
                String sub = subcategory(c.accountType(), best.accountType());
                out.put(c.key(), sub);
                out.put(best.key(), sub);
                used.add(c.key());
                used.add(best.key());
                continue;
            }
            // Then rows already stored.
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select t.id, a.type, t.plaid_pfc_primary, t.plaid_pfc_confidence, t.category
                    from transaction t join account a on a.id = t.account_id
                    where a.role = 'ledger' and not a.hidden and t.account_id <> ? and t.amount = ?
                      and t.date between ? and ? and not t.reviewed and t.category_source is distinct from 'user'
                    order by (t.category = 'Transfer') nulls first, abs(t.date - ?::date), t.seq
                    limit 5""",
                    c.accountId(), c.amount().negate(), c.date().minusDays(WINDOW_DAYS), c.date().plusDays(WINDOW_DAYS), c.date());
            for (Map<String, Object> r : rows) {
                if (!pfcAllows((String) r.get("plaid_pfc_primary"), (String) r.get("plaid_pfc_confidence"))) {
                    continue;
                }
                String sub = subcategory(c.accountType(), (String) r.get("type"));
                out.put(c.key(), sub);
                used.add(c.key());
                if (!"Transfer".equals(r.get("category"))) {
                    jdbc.update("""
                            update transaction set category = 'Transfer', subcategory = ?, category_source = 'rule',
                                   confidence = ?, updated_at = now()
                            where id = ?""", sub, PAIR_CONFIDENCE, r.get("id"));
                }
                break;
            }
        }
        return out;
    }

    static String subcategory(String typeA, String typeB) {
        return "credit".equals(typeA) || "credit".equals(typeB) ? TransferDetector.CARD_PAYMENT : TransferDetector.ACCOUNT_TRANSFER;
    }

    private static boolean eligible(Side s) {
        return "ledger".equals(s.accountRole()) && s.amount().abs().compareTo(MIN_AMOUNT) >= 0
                && pfcAllows(s.pfcPrimary(), s.pfcConfidence());
    }

    /** Plaid confidently saying "this is a purchase / income" vetoes pairing. */
    static boolean pfcAllows(String primary, String confidence) {
        if (primary == null || primary.startsWith("TRANSFER") || primary.startsWith("LOAN")) {
            return true;
        }
        return PfcMapper.confidence(confidence).compareTo(new BigDecimal("0.85")) < 0;
    }
}
