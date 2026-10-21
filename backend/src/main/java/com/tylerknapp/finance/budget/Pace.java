package com.tylerknapp.finance.budget;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;

/** Pace and status rules, ported from the prototype's elapsed() and renderBudget(). */
public final class Pace {
    private Pace() {}

    static final BigDecimal AHEAD_FACTOR = new BigDecimal("1.1");
    static final BigDecimal OVER_TOLERANCE = new BigDecimal("0.005");

    public enum PeriodState { past, current, future }

    public record Status(String status, String label) {}

    /** 1 for past months, day-of-month / days-in-month for the current month, 0 for future months. */
    public static BigDecimal elapsed(YearMonth month, LocalDate today) {
        YearMonth now = YearMonth.from(today);
        if (month.isBefore(now)) {
            return BigDecimal.ONE;
        }
        if (month.isAfter(now)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(today.getDayOfMonth())
                .divide(BigDecimal.valueOf(month.lengthOfMonth()), MathContext.DECIMAL64);
    }

    /** Past = every month is before this month; future = every month is after it. */
    public static PeriodState state(List<YearMonth> months, LocalDate today) {
        YearMonth now = YearMonth.from(today);
        if (months.stream().allMatch(m -> m.isBefore(now))) {
            return PeriodState.past;
        }
        if (months.stream().allMatch(m -> m.isAfter(now))) {
            return PeriodState.future;
        }
        return PeriodState.current;
    }

    /**
     * Status pill, same order of checks as the prototype: over, then finished periods
     * (on budget / under), then future (not started), then ahead of pace, else on track.
     */
    public static Status status(BigDecimal spent, BigDecimal budget, BigDecimal expected, PeriodState state) {
        if (spent.compareTo(budget.add(OVER_TOLERANCE)) > 0) {
            return new Status("over", "Over by " + wholeDollars(spent.subtract(budget)));
        }
        if (state == PeriodState.past) {
            BigDecimal left = budget.subtract(spent);
            return new Status("ok", left.compareTo(BigDecimal.ONE) < 0 ? "On budget" : "Under by " + wholeDollars(left));
        }
        if (state == PeriodState.future) {
            return new Status("ok", "Not started");
        }
        if (spent.compareTo(expected.multiply(AHEAD_FACTOR)) > 0 && spent.signum() > 0) {
            return new Status("warn", "Ahead of pace");
        }
        return new Status("ok", "On track");
    }

    /** The prototype's fmt(n, false): "$1,234", rounded half away from zero like toLocaleString. */
    public static String wholeDollars(BigDecimal n) {
        DecimalFormat f = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return (n.signum() < 0 ? "−" : "") + "$" + f.format(n.abs());
    }
}
