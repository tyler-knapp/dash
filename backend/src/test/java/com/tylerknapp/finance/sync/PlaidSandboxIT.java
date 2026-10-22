package com.tylerknapp.finance.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;

import com.tylerknapp.finance.PostgresTestBase;
import com.tylerknapp.finance.crypto.CryptoConfig.TokenCipherProvider;
import com.tylerknapp.finance.link.LinkService;
import com.tylerknapp.finance.plaid.PlaidGateway;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Against the real Plaid Sandbox. Runs only with PLAID_SANDBOX_IT=true plus PLAID_CLIENT_ID and
 * PLAID_SECRET for Sandbox, so CI without keys still passes. Refuses to run unless PLAID_ENV is
 * sandbox (never calls production).
 *
 * <p>Run: {@code PLAID_SANDBOX_IT=true PLAID_ENV=sandbox PLAID_CLIENT_ID=... PLAID_SECRET=... ./gradlew test --tests '*PlaidSandboxIT'}
 */
@EnabledIfEnvironmentVariable(named = "PLAID_SANDBOX_IT", matches = "true")
class PlaidSandboxIT extends PostgresTestBase {
    /** Plaid's test bank "First Platypus Bank" (non-OAuth); logs in as user_good / pass_good. */
    static final String SANDBOX_INSTITUTION = "ins_109508";

    @Autowired PlaidGateway plaid;
    @Autowired LinkService link;
    @Autowired SyncService sync;
    @Autowired TokenCipherProvider cipher;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.tylerknapp.finance.categorize.TaxonomyRepository taxonomy;

    @BeforeEach
    void guard() {
        String env = System.getenv("PLAID_ENV");
        assumeThat(env == null || env.equals("sandbox")).as("PLAID_ENV must be sandbox").isTrue();
        assumeThat(System.getenv("PLAID_CLIENT_ID")).as("PLAID_CLIENT_ID").isNotBlank();
        jdbc.execute("truncate item, account, transaction, balance_snapshot cascade");
    }

    @Test
    void linkSyncIncrementallyAndReconnect() {
        // Link without the UI: Sandbox can mint a public token for user_good / pass_good.
        String publicToken = plaid.sandboxCreatePublicToken(SANDBOX_INSTITUTION, List.of("transactions"));
        UUID itemId = link.exchange(publicToken).itemId();

        assertThat(jdbc.queryForObject("select count(*) from account where item_id = ?", Integer.class, itemId)).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from balance_snapshot", Integer.class)).isPositive();

        // Plaid pulls history in the background; keep syncing until it says it's done.
        await().atMost(Duration.ofMinutes(4)).pollInterval(Duration.ofSeconds(10)).until(() -> {
            sync.syncItem(itemId);
            return "HISTORICAL_UPDATE_COMPLETE".equals(jdbc.queryForObject(
                    "select transactions_update_status from item where id = ?", String.class, itemId));
        });
        int total = jdbc.queryForObject("select count(*) from transaction", Integer.class);
        assertThat(total).isPositive();

        // Sign flip: compare a stored amount with Plaid's raw amount for the same transaction.
        String accessToken = cipher.get().decrypt(
                jdbc.queryForObject("select access_token_enc from item where id = ?", byte[].class, itemId));
        PlaidGateway.Txn raw = plaid.syncTransactions(accessToken, null, 50, 730).added().get(0);
        BigDecimal stored = jdbc.queryForObject(
                "select amount from transaction where plaid_transaction_id = ?", BigDecimal.class, raw.transactionId());
        assertThat(stored).isEqualByComparingTo(raw.amount().negate());

        // Phase 4: every Sandbox transaction has a valid category, and card payments never count as spending.
        var tax = taxonomy.load();
        assertThat(jdbc.queryForList("select category, subcategory from transaction")).allSatisfy(r ->
                assertThat(tax.isValid((String) r.get("category"), (String) r.get("subcategory"))).as(r.toString()).isTrue());
        assertThat(jdbc.queryForObject("""
                select count(*) from transaction where plaid_pfc_detailed like '%CREDIT_CARD_PAYMENT%'
                  and (category <> 'Transfer' or subcategory <> 'Card Payment')""", Integer.class)).isZero();

        // Second sync is incremental: resumes from the saved cursor and adds nothing new.
        String cursor = jdbc.queryForObject("select sync_cursor from item where id = ?", String.class, itemId);
        assertThat(cursor).isNotBlank();
        SyncResult again = sync.syncItem(itemId);
        assertThat(again.outcome()).isEqualTo("ok");
        assertThat(again.added()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from transaction", Integer.class)).isEqualTo(total);

        // Broken login -> login_required, and update mode can be started for that Item.
        plaid.sandboxResetLogin(accessToken);
        assertThat(sync.syncItem(itemId).outcome()).isEqualTo("login_required");
        assertThat(jdbc.queryForObject("select status from item where id = ?", String.class, itemId))
                .isEqualTo("login_required");
        assertThat(link.createLinkToken(itemId, null)).startsWith("link-sandbox-");
    }
}
