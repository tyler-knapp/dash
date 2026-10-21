package com.tylerknapp.finance.link;

import com.tylerknapp.finance.config.AppProperties;
import com.tylerknapp.finance.crypto.CryptoConfig.TokenCipherProvider;
import com.tylerknapp.finance.plaid.PlaidGateway;
import com.tylerknapp.finance.store.ItemRepository;
import com.tylerknapp.finance.sync.ItemNotFoundException;
import com.tylerknapp.finance.sync.SyncResult;
import com.tylerknapp.finance.sync.SyncScheduler;
import com.tylerknapp.finance.sync.SyncService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Plaid Link: link tokens (new and update mode) and the public-token exchange (SPEC §5). */
@Service
public class LinkService {
    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    /** Single-user app: one stable Plaid client_user_id. */
    static final String CLIENT_USER_ID = "owner";

    private final PlaidGateway plaid;
    private final TokenCipherProvider cipher;
    private final ItemRepository items;
    private final SyncService sync;
    private final SyncScheduler scheduler;
    private final AppProperties props;

    public LinkService(PlaidGateway plaid, TokenCipherProvider cipher, ItemRepository items, SyncService sync,
                       SyncScheduler scheduler, AppProperties props) {
        this.plaid = plaid;
        this.cipher = cipher;
        this.items = items;
        this.sync = sync;
        this.scheduler = scheduler;
        this.props = props;
    }

    public enum Kind {
        /** Banks and cards: transactions (Vanguard also links this way, for its money market). */
        BANK(List.of("transactions")),
        /** 401(k)s and IRAs at retirement-only providers: balances via investments, no transactions. */
        RETIREMENT(List.of("investments"));

        final List<String> products;

        Kind(List<String> products) {
            this.products = products;
        }
    }

    /** @param itemId set for update mode (Reconnect): re-authenticates that Item and uses no new slot. */
    public String createLinkToken(UUID itemId, Kind kind) {
        String redirect = props.plaid().redirectUri();
        if (itemId != null) {
            var item = items.find(itemId).orElseThrow(() -> new ItemNotFoundException(itemId));
            String accessToken = cipher.get().decrypt(item.accessTokenEnc());
            return plaid.createLinkToken(new PlaidGateway.LinkTokenSpec(CLIENT_USER_ID, List.of(), accessToken, 0, redirect));
        }
        Kind k = kind == null ? Kind.BANK : kind;
        return plaid.createLinkToken(new PlaidGateway.LinkTokenSpec(CLIENT_USER_ID, k.products, null,
                730, redirect));
    }

    public record Linked(UUID itemId, SyncResult firstSync) {}

    /** Exchange, store the encrypted token, fetch accounts and run the first sync. */
    public Linked exchange(String publicToken) {
        cipher.get(); // fail before using up the public token if TOKEN_ENC_KEY is missing
        PlaidGateway.Exchanged ex = plaid.exchangePublicToken(publicToken);
        PlaidGateway.ItemInfo info = plaid.getItem(ex.accessToken());
        UUID id = items.upsert(ex.itemId(), info.institutionId(), info.institutionName(),
                cipher.get().encrypt(ex.accessToken()), info.products());
        log.info("Linked item {} ({}), products={}", id, info.institutionName(), info.products());
        SyncResult first = sync.syncItem(id);
        scheduler.scheduleFollowUps(id);
        return new Linked(id, first);
    }
}
