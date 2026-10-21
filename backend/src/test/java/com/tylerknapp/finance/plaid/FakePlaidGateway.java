package com.tylerknapp.finance.plaid;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Scriptable stand-in for Plaid. Each linked Item is keyed by its access token. */
public class FakePlaidGateway implements PlaidGateway {

    public static class FakeItem {
        public final String accessToken;
        public final String itemId;
        public String institutionName = "First Platypus Bank";
        public List<String> products = List.of("transactions");
        public List<Account> accounts = new ArrayList<>();
        public Map<String, BigDecimal> holdings = new HashMap<>();
        /** Queue of SyncPage or RuntimeException, consumed one per /transactions/sync call. */
        public final Deque<Object> syncScript = new ArrayDeque<>();
        /** If set, every call for this Item throws it (e.g. ITEM_LOGIN_REQUIRED). */
        public PlaidApiException failWith;
        public final List<String> cursorsSeen = new ArrayList<>();

        FakeItem(String accessToken, String itemId) {
            this.accessToken = accessToken;
            this.itemId = itemId;
        }
    }

    private final AtomicInteger seq = new AtomicInteger();
    public final Map<String, FakeItem> byPublicToken = new HashMap<>();
    public final Map<String, FakeItem> byAccessToken = new HashMap<>();
    public final List<LinkTokenSpec> linkSpecs = new ArrayList<>();

    public void reset() {
        byPublicToken.clear();
        byAccessToken.clear();
        linkSpecs.clear();
    }

    /** Prepare an Item that a later exchange of {@code publicToken} will "link". */
    public FakeItem prepare(String publicToken) {
        int n = seq.incrementAndGet();
        FakeItem item = new FakeItem("access-sandbox-fake-" + n, "item-" + n);
        byPublicToken.put(publicToken, item);
        byAccessToken.put(item.accessToken, item);
        return item;
    }

    @Override
    public String createLinkToken(LinkTokenSpec spec) {
        linkSpecs.add(spec);
        return "link-sandbox-" + linkSpecs.size();
    }

    @Override
    public Exchanged exchangePublicToken(String publicToken) {
        FakeItem i = byPublicToken.get(publicToken);
        if (i == null) {
            throw new PlaidApiException("INVALID_INPUT", "INVALID_PUBLIC_TOKEN", "bad token", null, "req-x");
        }
        return new Exchanged(i.accessToken, i.itemId);
    }

    @Override
    public ItemInfo getItem(String accessToken) {
        FakeItem i = item(accessToken);
        return new ItemInfo(i.itemId, "ins_" + i.itemId, i.institutionName, i.products);
    }

    @Override
    public SyncPage syncTransactions(String accessToken, String cursor, int count, int daysRequested) {
        FakeItem i = item(accessToken);
        i.cursorsSeen.add(cursor);
        Object next = i.syncScript.poll();
        if (next == null) {
            return new SyncPage(List.of(), List.of(), List.of(), cursor, false, "HISTORICAL_UPDATE_COMPLETE");
        }
        if (next instanceof RuntimeException e) {
            throw e;
        }
        return (SyncPage) next;
    }

    @Override
    public List<Account> getAccounts(String accessToken) {
        return item(accessToken).accounts;
    }

    @Override
    public Map<String, BigDecimal> holdingsValueByAccount(String accessToken) {
        return item(accessToken).holdings;
    }

    @Override
    public String sandboxCreatePublicToken(String institutionId, List<String> products) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sandboxResetLogin(String accessToken) {
        throw new UnsupportedOperationException();
    }

    private FakeItem item(String accessToken) {
        FakeItem i = byAccessToken.get(accessToken);
        if (i == null) {
            throw new PlaidApiException("INVALID_INPUT", "INVALID_ACCESS_TOKEN", "unknown token", null, "req-y");
        }
        if (i.failWith != null) {
            throw i.failWith;
        }
        return i;
    }

    // ---- builders for tests ----

    public static Account account(String id, String name, String type, String subtype, String current) {
        return new Account(id, name, null, id.length() > 4 ? id.substring(id.length() - 4) : id, type, subtype,
                current == null ? null : new BigDecimal(current), null);
    }

    /** {@code plaidAmount} in Plaid's sign: positive = money out. */
    public static Txn txn(String id, String accountId, String date, String plaidAmount, String merchant) {
        return new Txn(id, accountId, null, LocalDate.parse(date), null, merchant, merchant.toUpperCase(),
                new BigDecimal(plaidAmount), false, "GENERAL_MERCHANDISE", "GENERAL_MERCHANDISE_OTHER", "HIGH");
    }

    public static Txn pending(String id, String accountId, String date, String plaidAmount, String merchant) {
        Txn t = txn(id, accountId, date, plaidAmount, merchant);
        return new Txn(t.transactionId(), t.accountId(), null, t.date(), null, t.merchantName(), t.name(), t.amount(),
                true, t.pfcPrimary(), t.pfcDetailed(), t.pfcConfidence());
    }

    public static Txn posted(Txn from, String newId, String pendingId) {
        return new Txn(newId, from.accountId(), pendingId, from.date().plusDays(1), from.date(), from.merchantName(),
                from.name(), from.amount(), false, from.pfcPrimary(), from.pfcDetailed(), from.pfcConfidence());
    }

    public static SyncPage page(List<Txn> added, List<Txn> modified, List<String> removed, String next, boolean more) {
        return new SyncPage(added, modified, removed, next, more, "HISTORICAL_UPDATE_COMPLETE");
    }

    public static PlaidApiException loginRequired() {
        return new PlaidApiException("ITEM_ERROR", "ITEM_LOGIN_REQUIRED", "the login details of this item have changed",
                "Your bank login needs to be updated", "req-login");
    }
}
