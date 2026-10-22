package com.tylerknapp.finance.categorize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Re-runs the pipeline over transactions that are uncategorized or only have the low-confidence
 * fallback (e.g. synced before an API key was added). Never touches rows the user set or reviewed.
 */
@Service
@RestController
public class RecategorizeService {
    static final int LIMIT = 5000;

    private final JdbcTemplate jdbc;
    private final Categorizer categorizer;

    public RecategorizeService(JdbcTemplate jdbc, Categorizer categorizer) {
        this.jdbc = jdbc;
        this.categorizer = categorizer;
    }

    public record Result(int considered, int updated) {}

    @PostMapping("/api/transactions/categorize")
    @Transactional
    public Result run() {
        List<Categorizer.Candidate> candidates = jdbc.query("""
                select t.id, t.merchant_name, t.raw_name, t.amount, t.date, t.account_id, a.name, a.type, a.role,
                       t.plaid_pfc_primary, t.plaid_pfc_detailed, t.plaid_pfc_confidence
                from transaction t join account a on a.id = t.account_id
                where not a.is_sample and not t.reviewed and t.category_source is distinct from 'user'
                  and (t.category is null or (t.category_source = 'plaid' and t.confidence <= ?))
                order by t.date, t.seq limit ?""", (rs, i) -> new Categorizer.Candidate(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getBigDecimal(4), rs.getObject(5, LocalDate.class), rs.getObject(6, UUID.class),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12)),
                CategorizationPipeline.FALLBACK_CONFIDENCE, LIMIT);
        Map<String, Categorizer.Assignment> assigned = categorizer.categorize(candidates);
        int updated = 0;
        for (var e : assigned.entrySet()) {
            Categorizer.Assignment a = e.getValue();
            updated += jdbc.update("""
                    update transaction set category = ?, subcategory = ?, category_source = ?, confidence = ?, updated_at = now()
                    where id = ?::uuid and not reviewed and category_source is distinct from 'user'""",
                    a.category(), a.subcategory(), a.source(), a.confidence() == null ? null : a.confidence().min(BigDecimal.ONE), e.getKey());
        }
        return new Result(candidates.size(), updated);
    }
}
