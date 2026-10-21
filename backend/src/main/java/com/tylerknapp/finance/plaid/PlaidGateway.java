package com.tylerknapp.finance.plaid;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The few Plaid calls the app makes, in plain app types. The real implementation wraps the
 * official plaid-java client; tests use a fake. Amounts here are still in <b>Plaid's</b> sign
 * (positive = money out); the flip to app sign happens once, at ingest (see {@code Money}).
 */
public interface PlaidGateway {

    String createLinkToken(LinkTokenSpec spec);

    Exchanged exchangePublicToken(String publicToken);

    ItemInfo getItem(String accessToken);

    SyncPage syncTransactions(String accessToken, String cursor, int count, int daysRequested);

    /** Accounts with Plaid's cached balances (/accounts/get, no per-call Balance fee). */
    List<Account> getAccounts(String accessToken);

    /** Sum of holdings' institution_value per Plaid account id (fallback for missing retirement balances). */
    Map<String, BigDecimal> holdingsValueByAccount(String accessToken);

    /** Sandbox only: create a public token without the Link UI (used by integration tests). */
    String sandboxCreatePublicToken(String institutionId, List<String> products);

    /** Sandbox only: force an Item into ITEM_LOGIN_REQUIRED. */
    void sandboxResetLogin(String accessToken);

    /**
     * @param accessToken  set for update mode (re-authenticate an existing Item; no products, no new slot)
     * @param products     Plaid product names, e.g. "transactions" or "investments"
     */
    record LinkTokenSpec(String clientUserId, List<String> products, String accessToken, int daysRequested,
                         String redirectUri) {
        @Override
        public String toString() {
            return "LinkTokenSpec[products=" + products + ", updateMode=" + (accessToken != null) + "]";
        }
    }

    record Exchanged(String accessToken, String itemId) {
        @Override
        public String toString() {
            return "Exchanged[itemId=" + itemId + ", accessToken=<redacted>]";
        }
    }

    record ItemInfo(String itemId, String institutionId, String institutionName, List<String> products) {}

    record Account(String plaidAccountId, String name, String officialName, String mask, String type,
                   String subtype, BigDecimal current, BigDecimal available) {}

    /** One transaction as Plaid reports it. {@code amount} is in Plaid's sign. */
    record Txn(String transactionId, String accountId, String pendingTransactionId, LocalDate date,
               LocalDate authorizedDate, String merchantName, String name, BigDecimal amount, boolean pending,
               String pfcPrimary, String pfcDetailed, String pfcConfidence) {}

    record SyncPage(List<Txn> added, List<Txn> modified, List<String> removed, String nextCursor,
                    boolean hasMore, String updateStatus) {}
}
