package com.tylerknapp.finance.store;

import java.math.BigDecimal;

/**
 * Sign conventions. App: negative = money out, positive = money in. Plaid is the opposite for
 * transactions, and reports credit/loan balances as positive amounts owed. Both flips happen here,
 * once, at ingest.
 */
public final class Money {
    private Money() {}

    /** Plaid transaction amount (positive = money out) to app sign. */
    public static BigDecimal txnFromPlaid(BigDecimal plaidAmount) {
        return plaidAmount == null ? null : plaidAmount.negate();
    }

    /** Plaid balance to app sign: what you owe on cards and loans counts against net worth. */
    public static BigDecimal balanceFromPlaid(String accountType, BigDecimal plaidCurrent) {
        if (plaidCurrent == null) {
            return null;
        }
        return isLiability(accountType) ? plaidCurrent.negate() : plaidCurrent;
    }

    public static boolean isLiability(String accountType) {
        return "credit".equals(accountType) || "loan".equals(accountType);
    }
}
