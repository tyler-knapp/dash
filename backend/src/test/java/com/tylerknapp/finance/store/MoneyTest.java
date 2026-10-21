package com.tylerknapp.finance.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void plaidPurchaseBecomesNegative() {
        assertThat(Money.txnFromPlaid(new BigDecimal("12.34"))).isEqualByComparingTo("-12.34");
    }

    @Test
    void plaidDepositBecomesPositive() {
        assertThat(Money.txnFromPlaid(new BigDecimal("-3412.55"))).isEqualByComparingTo("3412.55");
    }

    @Test
    void cardAndLoanBalancesCountAgainstNetWorth() {
        assertThat(Money.balanceFromPlaid("credit", new BigDecimal("412.66"))).isEqualByComparingTo("-412.66");
        assertThat(Money.balanceFromPlaid("loan", new BigDecimal("1000"))).isEqualByComparingTo("-1000");
        assertThat(Money.balanceFromPlaid("depository", new BigDecimal("4218.40"))).isEqualByComparingTo("4218.40");
        assertThat(Money.balanceFromPlaid("investment", new BigDecimal("48210"))).isEqualByComparingTo("48210");
        assertThat(Money.balanceFromPlaid("credit", null)).isNull();
    }
}
