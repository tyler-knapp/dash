package com.tylerknapp.finance.sync;

import com.tylerknapp.finance.config.AppProperties;
import com.tylerknapp.finance.crypto.CryptoConfig.TokenCipherProvider;
import com.tylerknapp.finance.plaid.PlaidApiException;
import com.tylerknapp.finance.plaid.PlaidGateway;
import com.tylerknapp.finance.store.ItemRepository;
import com.tylerknapp.finance.store.ItemRepository.ItemRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Syncs Items from Plaid (SPEC §5): pages through /transactions/sync from the stored cursor,
 * then hands everything to {@link SyncApplier} to write in one DB transaction.
 */
@Service
public class SyncService {
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    static final int PAGE_SIZE = 500;
    static final int DAYS_REQUESTED = 730;
    static final int MAX_PAGINATION_RESTARTS = 3;
    static final int MAX_PAGES = 2000;

    private final ItemRepository items;
    private final PlaidGateway plaid;
    private final TokenCipherProvider cipher;
    private final SyncApplier applier;
    private final Clock clock;
    private final AppProperties props;
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public SyncService(ItemRepository items, PlaidGateway plaid, TokenCipherProvider cipher, SyncApplier applier,
                       Clock clock, AppProperties props) {
        this.items = items;
        this.plaid = plaid;
        this.cipher = cipher;
        this.applier = applier;
        this.clock = clock;
        this.props = props;
    }

    /** Sync every Item. One Item failing never stops the others. */
    public List<SyncResult> syncAll() {
        List<SyncResult> out = new ArrayList<>();
        for (ItemRow item : items.findAll()) {
            try {
                out.add(syncItem(item.id()));
            } catch (RuntimeException e) {
                log.error("Sync of item {} failed unexpectedly: {}", item.id(), e.getClass().getSimpleName(), e);
                out.add(SyncResult.failed(item.id(), item.institutionName(), "error", "INTERNAL", "Unexpected error"));
            }
        }
        return out;
    }

    public SyncResult syncItem(UUID itemId) {
        ReentrantLock lock = locks.computeIfAbsent(itemId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            return SyncResult.failed(itemId, null, "skipped", null, "A sync for this account is already running");
        }
        try {
            ItemRow item = items.find(itemId).orElseThrow(() -> new ItemNotFoundException(itemId));
            return doSync(item);
        } finally {
            lock.unlock();
        }
    }

    private SyncResult doSync(ItemRow item) {
        String accessToken = cipher.get().decrypt(item.accessTokenEnc());
        try {
            SyncApplier.FetchedTxns fetched = item.hasProduct("transactions")
                    ? fetchAllPages(accessToken, item.syncCursor()) : null;
            List<PlaidGateway.Account> accounts = plaid.getAccounts(accessToken);
            Map<String, BigDecimal> holdings = needsHoldings(item, accounts)
                    ? plaid.holdingsValueByAccount(accessToken) : Map.of();
            LocalDate today = LocalDate.now(clock.withZone(props.timeZone()));
            SyncApplier.Counts counts = applier.apply(item.id(), accounts, holdings, fetched, today);
            log.info("Synced item {} ({}): +{} ~{} -{}", item.id(), item.institutionName(),
                    counts.added(), counts.modified(), counts.removed());
            return SyncResult.ok(item.id(), item.institutionName(), counts);
        } catch (PlaidApiException e) {
            String status = e.isLoginRequired() ? "login_required" : "error";
            items.markError(item.id(), status, e.errorCode());
            log.warn("Sync of item {} failed: error_code={} request_id={}", item.id(), e.errorCode(), e.requestId());
            return SyncResult.failed(item.id(), item.institutionName(), status, e.errorCode(),
                    e.displayMessage() != null ? e.displayMessage() : e.errorCode());
        }
    }

    /** Retirement balances: use holdings totals only when Plaid gave no balance on the account object. */
    private static boolean needsHoldings(ItemRow item, List<PlaidGateway.Account> accounts) {
        return item.hasProduct("investments")
                && accounts.stream().anyMatch(a -> "investment".equals(a.type()) && a.current() == null);
    }

    /**
     * Loop while has_more. If Plaid reports the data changed mid-pagination, restart from the
     * original cursor (Plaid's documented recovery). Nothing is written until all pages are in.
     */
    SyncApplier.FetchedTxns fetchAllPages(String accessToken, String startCursor) {
        for (int attempt = 1; ; attempt++) {
            try {
                List<PlaidGateway.Txn> added = new ArrayList<>();
                List<PlaidGateway.Txn> modified = new ArrayList<>();
                List<String> removed = new ArrayList<>();
                String cursor = startCursor;
                String status = null;
                for (int page = 0; page < MAX_PAGES; page++) {
                    PlaidGateway.SyncPage p = plaid.syncTransactions(accessToken, cursor, PAGE_SIZE, DAYS_REQUESTED);
                    added.addAll(p.added());
                    modified.addAll(p.modified());
                    removed.addAll(p.removed());
                    cursor = p.nextCursor();
                    status = p.updateStatus();
                    if (!p.hasMore()) {
                        return new SyncApplier.FetchedTxns(added, modified, removed, cursor, status);
                    }
                }
                throw new IllegalStateException("Plaid returned more than " + MAX_PAGES + " pages");
            } catch (PlaidApiException e) {
                if (e.isMutationDuringPagination() && attempt < MAX_PAGINATION_RESTARTS) {
                    log.info("Plaid data changed during pagination; restarting from saved cursor (attempt {})", attempt + 1);
                    continue;
                }
                throw e;
            }
        }
    }
}
