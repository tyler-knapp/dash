package com.tylerknapp.finance.ledger;

import com.tylerknapp.finance.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LedgerController {
    private final TransactionQueries queries;
    private final JdbcTemplate jdbc;

    public LedgerController(TransactionQueries queries, JdbcTemplate jdbc) {
        this.queries = queries;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/transactions")
    public TransactionQueries.Page list(@RequestParam String from, @RequestParam String to,
                                        @RequestParam(required = false) UUID account,
                                        @RequestParam(required = false) String category,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "false") boolean needsReview,
                                        @RequestParam(defaultValue = "false") boolean includeTransfers,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(defaultValue = "" + TransactionQueries.DEFAULT_LIMIT) int limit) {
        var f = Months.parse("from", from);
        var t = Months.parse("to", to);
        if (t.isBefore(f)) { // the prototype's custom range accepts either order
            var tmp = f;
            f = t;
            t = tmp;
        }
        return queries.list(new TransactionQueries.Filter(f, t, account, blankToNull(category), q, needsReview,
                includeTransfers, cursor, limit));
    }

    public record Recategorize(@NotBlank String category, @NotBlank String subcategory) {}

    /** User override (SPEC §7): category_source = user, reviewed = true. */
    @PatchMapping("/api/transactions/{id}")
    @Transactional
    public TransactionQueries.TxnView recategorize(@PathVariable UUID id, @Valid @RequestBody Recategorize body) {
        Boolean known = jdbc.queryForObject(
                "select exists (select 1 from subcategory where category_name = ? and name = ?)", Boolean.class,
                body.category(), body.subcategory());
        if (!Boolean.TRUE.equals(known)) {
            throw new IllegalArgumentException("Unknown category: " + body.category() + " › " + body.subcategory());
        }
        int n = jdbc.update("""
                update transaction set category = ?, subcategory = ?, category_source = 'user', reviewed = true,
                       updated_at = now()
                where id = ?""", body.category(), body.subcategory(), id);
        if (n == 0) {
            throw new NotFoundException("No transaction with id " + id);
        }
        return queries.find(id).orElseThrow();
    }

    public record CategoryView(String name, String kind, List<String> subcategories) {}

    @GetMapping("/api/categories")
    public List<CategoryView> categories() {
        Map<String, CategoryView> out = new LinkedHashMap<>();
        jdbc.query("select name, kind from category order by sort_order", rs -> {
            out.put(rs.getString(1), new CategoryView(rs.getString(1), rs.getString(2), new ArrayList<>()));
        });
        jdbc.query("select category_name, name from subcategory order by sort_order", rs -> {
            out.get(rs.getString(1)).subcategories().add(rs.getString(2));
        });
        return new ArrayList<>(out.values());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
