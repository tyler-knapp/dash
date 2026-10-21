package com.tylerknapp.finance.sync;

import static com.tylerknapp.finance.plaid.FakePlaidGateway.account;
import static com.tylerknapp.finance.plaid.FakePlaidGateway.txn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tylerknapp.finance.PostgresTestBase;
import com.tylerknapp.finance.plaid.FakePlaidGateway;
import com.tylerknapp.finance.plaid.PlaidGateway.SyncPage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** After linking, the app keeps re-syncing until Plaid says the history pull is complete, then stops. */
@Import(SyncIntegrationTest.FakePlaid.class)
@TestPropertySource(properties = "app.sync.follow-up-delays=100ms, 100ms, 100ms, 100ms, 100ms")
class FollowUpSyncTest extends PostgresTestBase {
    @Autowired FakePlaidGateway plaid;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    void followUpsPullHistoryThenStop() throws Exception {
        plaid.reset();
        jdbc.execute("truncate item, account, transaction, balance_snapshot cascade");
        var item = plaid.prepare("public-fu");
        item.accounts = List.of(account("chk-0001", "Checking", "depository", "checking", "100.00"));
        item.syncScript.add(new SyncPage(List.of(), List.of(), List.of(), "c0", false, "NOT_READY"));
        item.syncScript.add(new SyncPage(List.of(txn("t1", "chk-0001", "2026-10-01", "5.00", "Recent")),
                List.of(), List.of(), "c1", false, "INITIAL_UPDATE_COMPLETE"));
        item.syncScript.add(new SyncPage(List.of(txn("t0", "chk-0001", "2025-01-01", "9.00", "Old")),
                List.of(), List.of(), "c2", false, "HISTORICAL_UPDATE_COMPLETE"));

        http.postForEntity("/api/plaid/exchange", Map.of("publicToken", "public-fu"), Map.class);

        await().atMost(Duration.ofSeconds(10)).until(
                () -> jdbc.queryForObject("select count(*) from transaction", Integer.class) == 2);
        assertThat(jdbc.queryForObject("select transactions_update_status from item", String.class))
                .isEqualTo("HISTORICAL_UPDATE_COMPLETE");
        Thread.sleep(600); // remaining follow-up slots would have fired by now
        assertThat(item.cursorsSeen).containsExactly(null, "c0", "c1");
    }
}
