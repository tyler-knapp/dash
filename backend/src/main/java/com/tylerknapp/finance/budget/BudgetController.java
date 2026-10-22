package com.tylerknapp.finance.budget;

import com.tylerknapp.finance.ledger.Months;
import com.tylerknapp.finance.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BudgetController {
    private final BudgetRepository budgets;
    private final SummaryService summary;
    private final JdbcTemplate jdbc;

    public BudgetController(BudgetRepository budgets, SummaryService summary, JdbcTemplate jdbc) {
        this.budgets = budgets;
        this.summary = summary;
        this.jdbc = jdbc;
    }

    public record VersionView(String effectiveFrom, Map<String, BigDecimal> amounts) {}

    @GetMapping("/api/budgets")
    public List<VersionView> versions() {
        return budgets.load().versions().stream()
                .map(v -> new VersionView(v.effectiveFrom().toString(), v.amounts())).toList();
    }

    public record SetBudget(@NotNull @DecimalMin("0") @DecimalMax("99999999") @Digits(integer = 8, fraction = 2) BigDecimal amount) {}

    /** Upsert the version for {month} and change only {category} (SPEC §6). Earlier months never change. */
    @PutMapping("/api/budgets/{month}/{category}")
    @Transactional
    public VersionView set(@PathVariable String month, @PathVariable String category, @Valid @RequestBody SetBudget body) {
        YearMonth m = Months.parse("month", month);
        String kind = jdbc.query("select kind from category where name = ?", (rs, i) -> rs.getString(1), category)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Unknown category: " + category));
        if (!"spend".equals(kind)) {
            throw new IllegalArgumentException("Only spending categories have budgets");
        }
        budgets.lock();
        BudgetHistory.Version v = budgets.load().edit(m, category, body.amount());
        budgets.save(v);
        return new VersionView(v.effectiveFrom().toString(), v.amounts());
    }

    @GetMapping("/api/summary")
    public SummaryService.Summary summary(@RequestParam String from, @RequestParam String to) {
        YearMonth f = Months.parse("from", from);
        YearMonth t = Months.parse("to", to);
        if (t.isBefore(f)) {
            YearMonth tmp = f;
            f = t;
            t = tmp;
        }
        if (f.plusYears(10).isBefore(t)) {
            throw new IllegalArgumentException("Range is too long (max 10 years)");
        }
        return summary.summarize(f, t);
    }
}
