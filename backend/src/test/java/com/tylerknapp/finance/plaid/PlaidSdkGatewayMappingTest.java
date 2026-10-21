package com.tylerknapp.finance.plaid;

import static org.assertj.core.api.Assertions.assertThat;

import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountSubtype;
import com.plaid.client.model.AccountType;
import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.Transaction;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Mapping from the official client's models; amounts stay in Plaid's sign here. */
class PlaidSdkGatewayMappingTest {

    @Test
    void mapsTransactionWithoutFloatingPointDrift() {
        Transaction t = new Transaction()
                .transactionId("tx1").accountId("acc1").pendingTransactionId("p1")
                .date(LocalDate.of(2026, 10, 2)).authorizedDate(LocalDate.of(2026, 10, 1))
                .merchantName("Kroger").name("KROGER #512 POLARIS").amount(118.42).pending(false)
                .personalFinanceCategory(new PersonalFinanceCategory().primary("FOOD_AND_DRINK")
                        .detailed("FOOD_AND_DRINK_GROCERIES").confidenceLevel("VERY_HIGH"));

        PlaidGateway.Txn m = PlaidSdkGateway.toTxn(t);

        assertThat(m.amount()).isEqualByComparingTo("118.42");
        assertThat(m.amount().scale()).isEqualTo(2);
        assertThat(m.pendingTransactionId()).isEqualTo("p1");
        assertThat(m.pfcDetailed()).isEqualTo("FOOD_AND_DRINK_GROCERIES");
        assertThat(m.pfcConfidence()).isEqualTo("VERY_HIGH");
        assertThat(m.name()).isEqualTo("KROGER #512 POLARIS");
    }

    @Test
    void moneyRoundsDoublesToCents() {
        assertThat(PlaidSdkGateway.money(0.1 + 0.2)).isEqualByComparingTo("0.30");
        assertThat(PlaidSdkGateway.money(1640)).isEqualByComparingTo("1640.00");
    }

    @Test
    void mapsAccountWithMissingBalance() {
        AccountBase a = new AccountBase().accountId("a1").name("Roth IRA").mask("1234")
                .type(AccountType.INVESTMENT).subtype(AccountSubtype.ROTH)
                .balances(new AccountBalance().current(null).available(null));
        PlaidGateway.Account m = PlaidSdkGateway.toAccount(a);
        assertThat(m.type()).isEqualTo("investment");
        assertThat(m.subtype()).isEqualTo("roth");
        assertThat(m.current()).isNull();
    }
}
