package com.tylerknapp.finance.sync;

import com.tylerknapp.finance.categorize.Categorizer;
import com.tylerknapp.finance.plaid.PlaidGateway;
import com.tylerknapp.finance.store.AccountRepository;
import com.tylerknapp.finance.store.BalanceRepository;
import com.tylerknapp.finance.store.ItemRepository;
import com.tylerknapp.finance.store.Money;
import com.tylerknapp.finance.store.TransactionRepository;
import com.tylerknapp.finance.store.TransactionRepository.CategoryFields;
import com.tylerknapp.finance.store.TransactionRepository.PlaidFields;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one Item's fetched Plaid data in a single DB transaction: accounts, every transaction
 * page, the new cursor, today's balances and the Item's status. If anything fails, nothing is
 * written and the old cursor stays, so the next sync simply replays from there.
 */
@Component
public class SyncApplier {
    private static final Logger log = LoggerFactory.getLogger(SyncApplier.class);

    private final ItemRepository items;
    private final AccountRepository accounts;
    private final TransactionRepository txns;
    private final BalanceRepository balances;
    private final Categorizer categorizer;

    public SyncApplier(ItemRepository items, AccountRepository accounts, TransactionRepository txns,
                       BalanceRepository balances, Categorizer categorizer) {
        this.items = items;
        this.accounts = accounts;
        this.txns = txns;
        this.balances = balances;
        this.categorizer = categorizer;
    }

    /** All transaction pages from one /transactions/sync run, merged in order. */
    public record FetchedTxns(List<PlaidGateway.Txn> added, List<PlaidGateway.Txn> modified, List<String> removed,
                              String nextCursor, String updateStatus) {}

    public record Counts(int added, int modified, int removed) {}

    @Transactional
    public Counts apply(UUID itemId, List<PlaidGateway.Account> plaidAccounts, Map<String, BigDecimal> holdings,
                        FetchedTxns fetched, LocalDate today) {
        Map<String, UUID> accountIds = new HashMap<>();
        Map<String, PlaidGateway.Account> accountsById = new HashMap<>();
        for (PlaidGateway.Account a : plaidAccounts) {
            accountIds.put(a.plaidAccountId(), accounts.upsertFromPlaid(itemId, a));
            accountsById.put(a.plaidAccountId(), a);
        }

        Counts counts = new Counts(0, 0, 0);
        if (fetched != null) {
            counts = applyTransactions(fetched, accountIds, accountsById);
            items.saveCursor(itemId, fetched.nextCursor(), fetched.updateStatus());
        }

        for (PlaidGateway.Account a : plaidAccounts) {
            BigDecimal current = a.current() != null ? a.current() : holdings.get(a.plaidAccountId());
            if (current == null) {
                continue;
            }
            balances.upsert(accountIds.get(a.plaidAccountId()), today, Money.balanceFromPlaid(a.type(), current),
                    a.available(), "plaid");
        }
        items.markOk(itemId);
        return counts;
    }

    private Counts applyTransactions(FetchedTxns f, Map<String, UUID> accountIds, Map<String, PlaidGateway.Account> accountsById) {
        // Later pages win if Plaid repeats an id.
        Map<String, PlaidGateway.Txn> added = new LinkedHashMap<>();
        f.added().forEach(t -> added.put(t.transactionId(), t));
        Map<String, PlaidGateway.Txn> modified = new LinkedHashMap<>();
        f.modified().forEach(t -> modified.put(t.transactionId(), t));
        Set<String> removed = new LinkedHashSet<>(f.removed());

        // Pending -> posted: carry the pending row's category forward, so a user's edit survives settlement.
        // Read before anything is deleted (the pending row usually arrives in `removed` in the same run).
        Map<String, CategoryFields> carried = new HashMap<>();
        List<Categorizer.Candidate> toCategorize = new ArrayList<>();
        for (PlaidGateway.Txn t : added.values()) {
            if (removed.contains(t.transactionId())) {
                continue;
            }
            CategoryFields fromPending = t.pendingTransactionId() == null ? null
                    : txns.findCategory(t.pendingTransactionId()).filter(c -> c.category() != null).orElse(null);
            if (fromPending != null) {
                carried.put(t.transactionId(), fromPending);
            } else if (t.amount() != null && t.date() != null && accountIds.containsKey(t.accountId())) {
                PlaidGateway.Account acct = accountsById.get(t.accountId());
                toCategorize.add(new Categorizer.Candidate(t.transactionId(), t.merchantName(), t.name(),
                        Money.txnFromPlaid(t.amount()), t.date(), accountIds.get(t.accountId()), acct.name(), acct.type(),
                        AccountRepository.roleFor(acct.type()), t.pfcPrimary(), t.pfcDetailed(), t.pfcConfidence()));
            }
        }
        Map<String, Categorizer.Assignment> assigned =
                toCategorize.isEmpty() ? Map.of() : categorizer.categorize(toCategorize);

        int nAdded = 0;
        for (PlaidGateway.Txn t : added.values()) {
            if (removed.contains(t.transactionId())) {
                continue; // added and removed in the same run (e.g. a short-lived pending)
            }
            PlaidFields fields = toFields(t, accountIds);
            if (fields == null) {
                continue;
            }
            CategoryFields cat = carried.get(t.transactionId());
            if (cat == null) {
                Categorizer.Assignment a = assigned.get(t.transactionId());
                cat = a == null ? CategoryFields.NONE
                        : new CategoryFields(a.category(), a.subcategory(), a.source(), a.confidence(), false);
            }
            txns.insertAdded(fields, cat);
            nAdded++;
        }

        int nModified = 0;
        for (PlaidGateway.Txn t : modified.values()) {
            if (removed.contains(t.transactionId())) {
                continue;
            }
            PlaidFields fields = toFields(t, accountIds);
            if (fields == null) {
                continue;
            }
            // Category is kept as is. Phase 4 may re-run the categorizer here, but never when
            // category_source = user or reviewed = true.
            if (txns.updateModified(fields) == 0) {
                txns.insertAdded(fields, CategoryFields.NONE); // modified before we saw it added: keep it anyway
            }
            nModified++;
        }

        int nRemoved = txns.deleteByPlaidIds(removed);
        return new Counts(nAdded, nModified, nRemoved);
    }

    private PlaidFields toFields(PlaidGateway.Txn t, Map<String, UUID> accountIds) {
        UUID accountId = accountIds.get(t.accountId());
        if (accountId == null || t.amount() == null || t.date() == null) {
            log.warn("Skipping transaction with unknown account or missing amount/date (account known={})",
                    accountId != null);
            return null;
        }
        return new PlaidFields(accountId, t.transactionId(), t.pendingTransactionId(), t.date(), t.authorizedDate(),
                t.merchantName(), t.name(), Money.txnFromPlaid(t.amount()), t.pending(), t.pfcPrimary(),
                t.pfcDetailed(), t.pfcConfidence());
    }
}
