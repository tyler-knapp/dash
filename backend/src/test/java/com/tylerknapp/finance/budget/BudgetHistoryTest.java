package com.tylerknapp.finance.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC §6 budget versions, using reference/sample-data/budgets.json (Food goes 700 → 750 in July). */
class BudgetHistoryTest {
    static final YearMonth JAN = YearMonth.of(2026, 1);
    static final YearMonth JUL = YearMonth.of(2026, 7);
    static final YearMonth AUG = YearMonth.of(2026, 8);
    static final YearMonth SEP = YearMonth.of(2026, 9);
    static final YearMonth OCT = YearMonth.of(2026, 10);

    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    static final BudgetHistory SAMPLE = new BudgetHistory(List.of(
            new BudgetHistory.Version(JUL, Map.of("Housing", d("1900"), "Food", d("750"), "Transportation", d("380"),
                    "Health & Fitness", d("275"), "Subscriptions", d("150"), "Lifestyle", d("350"), "Side Hustles", d("300"))),
            new BudgetHistory.Version(JAN, Map.of("Housing", d("1900"), "Food", d("700"), "Transportation", d("380"),
                    "Health & Fitness", d("250"), "Subscriptions", d("150"), "Lifestyle", d("350"), "Side Hustles", d("300")))));

    @Test
    void versionAppliesFromItsMonthUntilTheNext() {
        assertThat(SAMPLE.budgetFor(YearMonth.of(2025, 12))).isEmpty();
        assertThat(SAMPLE.budgetFor(JAN, "Food")).isEqualByComparingTo("700");
        assertThat(SAMPLE.budgetFor(YearMonth.of(2026, 6), "Food")).isEqualByComparingTo("700");
        assertThat(SAMPLE.budgetFor(JUL, "Food")).isEqualByComparingTo("750");
        assertThat(SAMPLE.budgetFor(YearMonth.of(2027, 3), "Food")).isEqualByComparingTo("750");
        assertThat(SAMPLE.budgetFor(OCT, "Not a category")).isEqualByComparingTo("0");
    }

    @Test
    void editingOctoberDoesNotChangeSeptember() {
        BudgetHistory.Version oct = SAMPLE.edit(OCT, "Food", d("900"));
        BudgetHistory after = SAMPLE.with(oct);

        assertThat(after.budgetFor(OCT, "Food")).isEqualByComparingTo("900");
        assertThat(after.budgetFor(SEP, "Food")).isEqualByComparingTo("750");
        assertThat(after.budgetFor(SEP)).isEqualTo(SAMPLE.budgetFor(SEP));
        assertThat(after.budgetFor(JUL)).isEqualTo(SAMPLE.budgetFor(JUL));
        // The new October version copied every other amount in effect for October.
        assertThat(oct.amounts()).hasSize(7).containsEntry("Housing", d("1900")).containsEntry("Health & Fitness", d("275"));
        // Later months follow October's version.
        assertThat(after.budgetFor(YearMonth.of(2026, 12), "Food")).isEqualByComparingTo("900");
    }

    @Test
    void editingAMonthThatHasAVersionUpdatesOnlyThatCategory() {
        BudgetHistory after = SAMPLE.with(SAMPLE.edit(JUL, "Lifestyle", d("400")));
        assertThat(after.versions()).hasSize(2);
        assertThat(after.budgetFor(JUL, "Lifestyle")).isEqualByComparingTo("400");
        assertThat(after.budgetFor(JUL, "Food")).isEqualByComparingTo("750");
        assertThat(after.budgetFor(YearMonth.of(2026, 6), "Lifestyle")).isEqualByComparingTo("350");
    }

    @Test
    void editingAnEarlierMonthDoesNotOverrideALaterVersion() {
        BudgetHistory withOct = SAMPLE.with(SAMPLE.edit(OCT, "Food", d("900")));
        BudgetHistory after = withOct.with(withOct.edit(AUG, "Food", d("600")));
        assertThat(after.budgetFor(JUL, "Food")).isEqualByComparingTo("750");
        assertThat(after.budgetFor(AUG, "Food")).isEqualByComparingTo("600");
        assertThat(after.budgetFor(SEP, "Food")).isEqualByComparingTo("600");
        assertThat(after.budgetFor(OCT, "Food")).isEqualByComparingTo("900");
    }

    @Test
    void ytdBudgetSumsTheVersionInEffectEachMonth() {
        List<YearMonth> ytd = List.of(JAN, YearMonth.of(2026, 2), YearMonth.of(2026, 3), YearMonth.of(2026, 4),
                YearMonth.of(2026, 5), YearMonth.of(2026, 6), JUL, AUG, SEP, OCT);
        // Food: 6 months at 700 + 4 months at 750. Health & Fitness: 6 x 250 + 4 x 275.
        assertThat(SAMPLE.periodBudget(ytd, "Food")).isEqualByComparingTo("7200");
        assertThat(SAMPLE.periodBudget(ytd, "Health & Fitness")).isEqualByComparingTo("2600");
        assertThat(SAMPLE.periodBudget(ytd, "Housing")).isEqualByComparingTo("19000");
    }
}
