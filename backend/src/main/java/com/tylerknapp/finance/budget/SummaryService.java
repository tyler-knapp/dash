package com.tylerknapp.finance.budget;

import com.tylerknapp.finance.config.AppProperties;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * GET /api/summary (SPEC §6, §8): income, spending and budgets for a range of months, with the
 * prototype's pace and status rules. Only spend categories count against budgets and "Spent";
 * transfers count nowhere. Hidden accounts are left out, as in the ledger.
 */
@Service
public class SummaryService {
    private final JdbcTemplate jdbc;
    private final BudgetRepository budgets;
    private final Clock clock;
    private final AppProperties props;

    public SummaryService(JdbcTemplate jdbc, BudgetRepository budgets, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.budgets = budgets;
        this.clock = clock;
        this.props = props;
    }

    public record Sub(String name, BigDecimal spent) {}

    public record CategorySummary(String name, BigDecimal spent, BigDecimal budget, BigDecimal expectedToDate,
                                  String status, String statusLabel, List<Sub> subs) {}

    public record Summary(String from, String to, List<String> months, String periodState, LocalDate today,
                          int transactionCount, int uncategorizedCount, BigDecimal income, BigDecimal spent,
                          BigDecimal budgeted, BigDecimal net, List<CategorySummary> categories) {}

    public LocalDate today() {
        return LocalDate.now(clock.withZone(props.timeZone()));
    }

    public Summary summarize(YearMonth from, YearMonth to) {
        LocalDate today = today();
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            months.add(m);
        }
        Pace.PeriodState state = Pace.state(months, today);
        BudgetHistory history = budgets.load();

        // Taxonomy, in order.
        Map<String, String> kinds = new LinkedHashMap<>();
        jdbc.query("select name, kind from category order by sort_order", rs -> {
            kinds.put(rs.getString(1), rs.getString(2));
        });
        Map<String, List<String>> subsByCat = new LinkedHashMap<>();
        jdbc.query("select category_name, name from subcategory order by sort_order", rs -> {
            subsByCat.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
        });

        // Totals per (category, subcategory) in the period; amounts are app sign.
        Map<String, BigDecimal> byCat = new HashMap<>();
        Map<String, BigDecimal> bySub = new HashMap<>();
        int[] counts = new int[2]; // [all, uncategorized]
        jdbc.query("""
                select t.category, t.subcategory, sum(t.amount), count(*)
                from transaction t join account a on a.id = t.account_id
                where not a.hidden and t.date >= ? and t.date < ?
                group by t.category, t.subcategory""", rs -> {
            String cat = rs.getString(1);
            BigDecimal sum = rs.getBigDecimal(3);
            counts[0] += rs.getInt(4);
            if (cat == null) {
                counts[1] += rs.getInt(4);
                return;
            }
            byCat.merge(cat, sum, BigDecimal::add);
            bySub.merge(cat + "|" + rs.getString(2), sum, BigDecimal::add);
        }, from.atDay(1), to.plusMonths(1).atDay(1));

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal spent = BigDecimal.ZERO;
        BigDecimal budgeted = BigDecimal.ZERO;
        List<CategorySummary> cats = new ArrayList<>();
        for (var e : kinds.entrySet()) {
            String cat = e.getKey();
            BigDecimal total = byCat.getOrDefault(cat, BigDecimal.ZERO);
            if ("income".equals(e.getValue())) {
                income = income.add(total);
            }
            if (!"spend".equals(e.getValue())) {
                continue;
            }
            BigDecimal catSpent = total.negate(); // money out is negative; spending is shown positive
            spent = spent.add(catSpent);
            BigDecimal budget = history.periodBudget(months, cat);
            budgeted = budgeted.add(budget);
            BigDecimal expected = BigDecimal.ZERO;
            for (YearMonth m : months) {
                expected = expected.add(history.budgetFor(m, cat).multiply(Pace.elapsed(m, today), MathContext.DECIMAL64));
            }
            Pace.Status st = Pace.status(catSpent, budget, expected, state);
            List<Sub> subs = new ArrayList<>();
            for (String s : subsByCat.getOrDefault(cat, List.of())) {
                subs.add(new Sub(s, bySub.getOrDefault(cat + "|" + s, BigDecimal.ZERO).negate().setScale(2)));
            }
            cats.add(new CategorySummary(cat, catSpent.setScale(2), budget, expected.setScale(4, RoundingMode.HALF_UP),
                    st.status(), st.label(), subs));
        }
        return new Summary(from.toString(), to.toString(), months.stream().map(YearMonth::toString).toList(),
                state.name(), today, counts[0], counts[1], income.setScale(2), spent.setScale(2), budgeted,
                income.subtract(spent).setScale(2), cats);
    }
}
