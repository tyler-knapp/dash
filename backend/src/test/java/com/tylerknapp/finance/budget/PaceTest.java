package com.tylerknapp.finance.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.tylerknapp.finance.budget.Pace.PeriodState;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaceTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    static Pace.Status st(String spent, String budget, String expected, PeriodState s) {
        return Pace.status(new BigDecimal(spent), new BigDecimal(budget), new BigDecimal(expected), s);
    }

    @Test
    void elapsedIsOneZeroOrShareOfTheMonth() {
        assertThat(Pace.elapsed(YearMonth.of(2026, 9), TODAY)).isEqualByComparingTo("1");
        assertThat(Pace.elapsed(YearMonth.of(2026, 11), TODAY)).isEqualByComparingTo("0");
        assertThat(Pace.elapsed(YearMonth.of(2026, 10), TODAY).doubleValue()).isCloseTo(21.0 / 31, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void periodState() {
        assertThat(Pace.state(List.of(YearMonth.of(2026, 9)), TODAY)).isEqualTo(PeriodState.past);
        assertThat(Pace.state(List.of(YearMonth.of(2026, 10), YearMonth.of(2026, 11)), TODAY)).isEqualTo(PeriodState.current);
        assertThat(Pace.state(List.of(YearMonth.of(2026, 11)), TODAY)).isEqualTo(PeriodState.future);
    }

    @Test
    void statusLabelsInThePrototypesOrder() {
        assertThat(st("391.39", "275", "186.29", PeriodState.current)).isEqualTo(new Pace.Status("over", "Over by $116"));
        assertThat(st("275.004", "275", "275", PeriodState.past)).isEqualTo(new Pace.Status("ok", "On budget")); // within half a cent
        assertThat(st("274.50", "275", "275", PeriodState.past)).isEqualTo(new Pace.Status("ok", "On budget")); // under by < $1
        assertThat(st("1822.48", "1900", "1900", PeriodState.past)).isEqualTo(new Pace.Status("ok", "Under by $78"));
        assertThat(st("0", "300", "0", PeriodState.future)).isEqualTo(new Pace.Status("ok", "Not started"));
        assertThat(st("1806.31", "1900", "1287.10", PeriodState.current)).isEqualTo(new Pace.Status("warn", "Ahead of pace"));
        assertThat(st("461.06", "750", "508.06", PeriodState.current)).isEqualTo(new Pace.Status("ok", "On track"));
        assertThat(st("0", "0", "0", PeriodState.current)).isEqualTo(new Pace.Status("ok", "On track"));
        // Over beats every other rule, even in a future period.
        assertThat(st("10", "0", "0", PeriodState.future)).isEqualTo(new Pace.Status("over", "Over by $10"));
    }

    @Test
    void wholeDollarsRoundsHalfUpWithSeparators() {
        assertThat(Pace.wholeDollars(new BigDecimal("1699.97"))).isEqualTo("$1,700");
        assertThat(Pace.wholeDollars(new BigDecimal("77.50"))).isEqualTo("$78");
        assertThat(Pace.wholeDollars(new BigDecimal("77.49"))).isEqualTo("$77");
        assertThat(Pace.wholeDollars(new BigDecimal("-5"))).isEqualTo("−$5");
    }
}
