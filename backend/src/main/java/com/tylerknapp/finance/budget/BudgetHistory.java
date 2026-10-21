package com.tylerknapp.finance.budget;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Budget versions (SPEC §6), ported from the prototype's history() / budgetFor() / setBudget().
 * A version applies from its month until the next version. Immutable; edits return a new history.
 */
public final class BudgetHistory {

    public record Version(YearMonth effectiveFrom, Map<String, BigDecimal> amounts) {
        public Version {
            amounts = Map.copyOf(amounts);
        }
    }

    private final List<Version> versions; // sorted by month

    public BudgetHistory(List<Version> versions) {
        List<Version> sorted = new ArrayList<>(versions);
        sorted.sort(Comparator.comparing(Version::effectiveFrom));
        this.versions = List.copyOf(sorted);
    }

    public List<Version> versions() {
        return versions;
    }

    /** Amounts of the latest version with effective_from <= month; empty before the first version. */
    public Map<String, BigDecimal> budgetFor(YearMonth month) {
        Map<String, BigDecimal> cur = Map.of();
        for (Version v : versions) {
            if (!v.effectiveFrom().isAfter(month)) {
                cur = v.amounts();
            }
        }
        return cur;
    }

    public BigDecimal budgetFor(YearMonth month, String category) {
        return budgetFor(month).getOrDefault(category, BigDecimal.ZERO);
    }

    /**
     * Edit one category in month M: update the version for M, or create it by copying the
     * amounts in effect for M. Earlier months never change; later versions keep their own amounts.
     *
     * @return the version for M after the edit
     */
    public Version edit(YearMonth month, String category, BigDecimal amount) {
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        versions.stream().filter(v -> v.effectiveFrom().equals(month)).findFirst()
                .ifPresentOrElse(v -> amounts.putAll(v.amounts()), () -> amounts.putAll(budgetFor(month)));
        amounts.put(category, amount);
        return new Version(month, amounts);
    }

    /** The history with {@code v} added or replacing the version for its month. */
    public BudgetHistory with(Version v) {
        List<Version> next = new ArrayList<>(versions.stream().filter(x -> !x.effectiveFrom().equals(v.effectiveFrom())).toList());
        next.add(v);
        return new BudgetHistory(next);
    }

    /** Sum over the period's months of the budget in effect each month. */
    public BigDecimal periodBudget(List<YearMonth> months, String category) {
        return months.stream().map(m -> budgetFor(m, category)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
