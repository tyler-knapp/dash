package com.tylerknapp.finance.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransferDetectorTest {
    static final Set<String> CARDS = Set.of("voice", "huntington", "amex", "american", "express", "capital");

    static Optional<String> d(String pfcPrimary, String pfcDetailed, String text, String amount, String type) {
        return TransferDetector.detect(pfcPrimary, pfcDetailed, "HIGH", text, new BigDecimal(amount), type, CARDS)
                .map(TransferDetector.Detected::subcategory);
    }

    @Test
    void plaidCreditCardPaymentOnEitherSide() {
        assertThat(d("LOAN_PAYMENTS", "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT", "CREDIT CARD 3333 PAYMENT", "-25", "depository")).contains("Card Payment");
        assertThat(d("LOAN_PAYMENTS", "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT", "AUTOMATIC PAYMENT - THANK", "2078.50", "credit")).contains("Card Payment");
    }

    @Test
    void cardPaymentWordingFromTheSampleData() {
        // Bank side (money out), names from reference/sample-data.
        assertThat(d(null, null, "Amex payment", "-187.43", "depository")).contains("Card Payment");
        assertThat(d(null, null, "Capital One payment", "-578.03", "depository")).contains("Card Payment");
        assertThat(d(null, null, "Huntington Voice payment", "-412.66", "depository")).contains("Card Payment");
        // Card side (money in).
        assertThat(d(null, null, "Payment received", "187.43", "credit")).contains("Card Payment");
        assertThat(d(null, null, "Payment received – thank you", "412.66", "credit")).contains("Card Payment");
        assertThat(d(null, null, "Capital One payment received", "578.03", "credit")).contains("Card Payment");
    }

    @Test
    void notCardPayments() {
        assertThat(d("FOOD_AND_DRINK", "FOOD_AND_DRINK_GROCERIES", "Kroger", "-118.42", "credit")).isEmpty(); // a purchase
        assertThat(d(null, null, "Amazon refund", "64.20", "credit")).isEmpty(); // refund, not a payment
        assertThat(d(null, null, "Rent payment", "-1640", "depository")).isEmpty(); // a payment, but not to a card
        assertThat(d(null, null, "Payroll deposit", "3412.55", "depository")).isEmpty();
    }

    @Test
    void plaidTransfersExceptPersonToPerson() {
        assertThat(d("TRANSFER_OUT", "TRANSFER_OUT_ACCOUNT_TRANSFER", "Transfer to SoFi Savings", "-500", "depository")).contains("Account Transfer");
        assertThat(d("TRANSFER_IN", "TRANSFER_IN_ACCOUNT_TRANSFER", "Transfer from Fifth Third", "500", "depository")).contains("Account Transfer");
        assertThat(d("TRANSFER_OUT", "TRANSFER_OUT_INVESTMENT_AND_RETIREMENT_FUNDS", "Vanguard IRA contribution", "-500", "depository"))
                .contains("Retirement Contribution");
        assertThat(d("TRANSFER_OUT", "TRANSFER_OUT_ACCOUNT_TRANSFER", "Venmo payment", "-40", "depository")).isEmpty();
        assertThat(d("TRANSFER_OUT", "TRANSFER_OUT_ACCOUNT_TRANSFER", "Zelle to Sam", "-40", "depository")).isEmpty();
    }

    @Test
    void confidenceFollowsPlaid() {
        var low = TransferDetector.detect("LOAN_PAYMENTS", "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT", "LOW", "x", new BigDecimal("-5"), "depository", CARDS);
        assertThat(low.get().confidence()).isEqualByComparingTo("0.4"); // shows as Review
    }
}
