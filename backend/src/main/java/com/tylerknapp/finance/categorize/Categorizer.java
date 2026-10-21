package com.tylerknapp.finance.categorize;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Assigns categories to newly added transactions (SPEC §7). Phase 1 ships a no-op; Phase 4 adds
 * transfer detection, the PFC map and the Claude batch categorizer behind this interface.
 */
public interface Categorizer {

    /** @return assignments keyed by Plaid transaction id; a missing key leaves the transaction uncategorized */
    Map<String, Assignment> categorize(List<Candidate> candidates);

    /** {@code amount} is in app sign (negative = money out). */
    record Candidate(String plaidTransactionId, String merchantName, String rawName, BigDecimal amount,
                     String accountName, String pfcPrimary, String pfcDetailed, String pfcConfidence) {}

    /** {@code source} is one of rule | plaid | ai. */
    record Assignment(String category, String subcategory, String source, BigDecimal confidence) {}
}
