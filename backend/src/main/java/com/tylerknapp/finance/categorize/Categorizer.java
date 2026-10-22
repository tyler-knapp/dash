package com.tylerknapp.finance.categorize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assigns categories to newly added transactions (SPEC §7). See {@link CategorizationPipeline}.
 */
public interface Categorizer {

    /** @return assignments keyed by Plaid transaction id; a missing key leaves the transaction uncategorized */
    Map<String, Assignment> categorize(List<Candidate> candidates);

    /** {@code amount} is in app sign (negative = money out). {@code accountRole} is ledger / balance_only. */
    record Candidate(String plaidTransactionId, String merchantName, String rawName, BigDecimal amount,
                     LocalDate date, UUID accountId, String accountName, String accountType, String accountRole,
                     String pfcPrimary, String pfcDetailed, String pfcConfidence) {}

    /** {@code source} is one of rule | plaid | ai. */
    record Assignment(String category, String subcategory, String source, BigDecimal confidence) {}
}
