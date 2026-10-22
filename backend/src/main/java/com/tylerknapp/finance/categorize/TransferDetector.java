package com.tylerknapp.finance.categorize;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Transfer detection that needs no database (SPEC §7 step 2): Plaid's transfer and credit card
 * payment categories, plus wording that card payments use on either side. Card payments must
 * never count as spending, so both the checking-side and the card-side rows are caught.
 */
public final class TransferDetector {
    private TransferDetector() {}

    public static final String TRANSFER = "Transfer";
    public static final String CARD_PAYMENT = "Card Payment";
    public static final String ACCOUNT_TRANSFER = "Account Transfer";
    public static final String RETIREMENT = "Retirement Contribution";

    public record Detected(String subcategory, BigDecimal confidence) {}

    /** Person-to-person apps: Plaid often calls these transfers, but they're usually spending. Left to later steps. */
    static final Pattern P2P = Pattern.compile("(?i)\\b(venmo|zelle|cash ?app|paypal|apple cash)\\b");
    /** Card side: a payment landing on the card. */
    static final Pattern CARD_SIDE_PAYMENT = Pattern.compile("(?i)\\b(payment|pymt|pmt|autopay|thank you)\\b");
    /** Bank side: words of a card payment. Needs a payment word plus a card word or one of Tyler's card names. */
    static final Pattern PAYMENT_WORD = Pattern.compile("(?i)\\b(payment|pymt|pmt|autopay|epay(ment)?)\\b");
    static final Pattern CARD_WORD = Pattern.compile("(?i)\\b(credit ?card|crd|card ?member|amex|american express|capital one|discover|citi ?card|chase card)\\b");
    static final Pattern REFUND_WORD = Pattern.compile("(?i)\\b(refund|return|reversal|credit adj)");

    /**
     * @param text        merchant and raw name together
     * @param amount      app sign (negative = money out)
     * @param accountType Plaid account type (depository, credit, ...)
     * @param cardNames   lowercase words that identify Tyler's own credit cards ("amex", "voice", ...)
     */
    public static Optional<Detected> detect(String pfcPrimary, String pfcDetailed, String pfcConfidence, String text,
                                            BigDecimal amount, String accountType, Collection<String> cardNames) {
        String detailed = pfcDetailed == null ? "" : pfcDetailed.toUpperCase(Locale.ROOT);
        String primary = pfcPrimary == null ? "" : pfcPrimary.toUpperCase(Locale.ROOT);
        String t = text == null ? "" : text;
        BigDecimal pfcConf = PfcMapper.confidence(pfcConfidence);
        BigDecimal conf = pfcConf.signum() == 0 ? new BigDecimal("0.6") : pfcConf;

        // 1. Plaid says credit card payment.
        if (detailed.contains("CREDIT_CARD_PAYMENT")) {
            return Optional.of(new Detected(CARD_PAYMENT, conf));
        }
        // 2. Card-payment wording.
        boolean credit = "credit".equals(accountType);
        if (credit && amount.signum() > 0 && CARD_SIDE_PAYMENT.matcher(t).find() && !REFUND_WORD.matcher(t).find()) {
            return Optional.of(new Detected(CARD_PAYMENT, new BigDecimal("0.85")));
        }
        if (!credit && amount.signum() < 0 && PAYMENT_WORD.matcher(t).find()
                && (CARD_WORD.matcher(t).find() || mentionsCard(t, cardNames))) {
            return Optional.of(new Detected(CARD_PAYMENT, new BigDecimal("0.8")));
        }
        // 3. Plaid says transfer (but not person-to-person apps).
        if ((primary.startsWith("TRANSFER_IN") || primary.startsWith("TRANSFER_OUT")) && !P2P.matcher(t).find()) {
            boolean retirement = primary.startsWith("TRANSFER_OUT") && (detailed.contains("RETIREMENT") || detailed.contains("INVESTMENT"));
            return Optional.of(new Detected(retirement ? RETIREMENT : ACCOUNT_TRANSFER, conf));
        }
        return Optional.empty();
    }

    private static boolean mentionsCard(String text, Collection<String> cardNames) {
        String lower = text.toLowerCase(Locale.ROOT);
        return cardNames.stream().anyMatch(n -> Pattern.compile("\\b" + Pattern.quote(n) + "\\b").matcher(lower).find());
    }
}
