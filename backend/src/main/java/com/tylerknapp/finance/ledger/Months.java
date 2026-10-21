package com.tylerknapp.finance.ledger;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/** YYYY-MM parsing for API parameters. */
public final class Months {
    private Months() {}

    public static YearMonth parse(String param, String value) {
        if (value == null || !value.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw new IllegalArgumentException(param + " must be YYYY-MM");
        }
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(param + " must be YYYY-MM");
        }
    }
}
