package com.tylerknapp.finance.sync;

import static com.tylerknapp.finance.plaid.FakePlaidGateway.account;
import static com.tylerknapp.finance.plaid.FakePlaidGateway.page;
import static com.tylerknapp.finance.plaid.FakePlaidGateway.pending;
import static com.tylerknapp.finance.plaid.FakePlaidGateway.posted;
import static com.tylerknapp.finance.plaid.FakePlaidGateway.txn;
import static org.assertj.core.api.Assertions.assertThat;

import com.tylerknapp.finance.PostgresTestBase;
import com.tylerknapp.finance.plaid.FakePlaidGateway;
import com.tylerknapp.finance.plaid.FakePlaidGateway.FakeItem;
import com.tylerknapp.finance.plaid.PlaidApiException;
import com.tylerknapp.finance.plaid.PlaidGateway;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 1 sync behavior end to end (HTTP -> services -> Postgres) with Plaid faked.
 * Covers SPEC §5: added / modified / removed, pending -> posted with a preserved user category,
 * cursor handling, incremental second sync, balances, update mode and per-Item failures.
 */
@Import(SyncIntegrationTest.FakePlaid.class)
class SyncIntegrationTest extends PostgresTestBase {

    @TestConfiguration
    static class FakePlaid {
        @Bean
        @Primary
        FakePlaidGateway fakePlaidGateway() {
            return new FakePlaidGateway();
        }
    }

    @Autowired FakePlaidGateway plaid;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SyncService sync;
    @Autowired SyncScheduler scheduler;
    @Autowired com.tylerknapp.finance.sample.SampleDataService sampleData;

    @BeforeEach
    void clean() {
        plaid.reset();
        jdbc.execute("truncate item, account, transaction, balance_snapshot, budget_version cascade");
    }

    /** A bank login with checking + a credit card, linked through the API. */
    private UUID link(FakeItem item, String publicToken) {
        ResponseEntity<Map> res = http.postForEntity("/api/plaid/exchange", Map.of("publicToken", publicToken), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString((String) ((Map<?, ?>) res.getBody().get("item")).get("id"));
    }

    private FakeItem bank(String publicToken) {
        FakeItem i = plaid.prepare(publicToken);
        i.accounts = List.of(account("chk-0001", "Plaid Checking", "depository", "checking", "4218.40"),
                account("cc-0002", "Plaid Credit Card", "credit", "credit card", "412.66"));
        return i;
    }

    private BigDecimal amount(String plaidId) {
        return jdbc.queryForObject("select amount from transaction where plaid_transaction_id = ?", BigDecimal.class, plaidId);
    }

    private int txnCount() {
        return jdbc.queryForObject("select count(*) from transaction", Integer.class);
    }

    @Test
    void linkingLoadsAccountsTransactionsAndBalancesWithAppSign() {
        FakeItem item = bank("public-1");
        item.syncScript.add(page(List.of(
                txn("t1", "cc-0002", "2026-10-02", "118.42", "Kroger"),
                txn("t2", "chk-0001", "2026-10-01", "-3412.55", "Payroll")), List.of(), List.of(), "c1", false));

        ResponseEntity<Map> res = http.postForEntity("/api/plaid/exchange", Map.of("publicToken", "public-1"), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> view = (Map<?, ?>) res.getBody().get("item");
        assertThat(view.get("institutionName")).isEqualTo("First Platypus Bank");
        assertThat((List<?>) view.get("accounts")).hasSize(2);
        assertThat(((Map<?, ?>) res.getBody().get("firstSync")).get("added")).isEqualTo(2);
        assertThat(res.getBody().toString()).doesNotContain(item.accessToken);

        // Sign flipped once at ingest: purchase negative, paycheck positive.
        assertThat(amount("t1")).isEqualByComparingTo("-118.42");
        assertThat(amount("t2")).isEqualByComparingTo("3412.55");
        // Roles and balances: card balance counts against net worth.
        assertThat(jdbc.queryForList("select role from account order by type", String.class))
                .containsExactly("ledger", "ledger");
        assertThat(jdbc.queryForObject("""
                select b.current from balance_snapshot b join account a on a.id = b.account_id
                where a.plaid_account_id = 'cc-0002'""", BigDecimal.class)).isEqualByComparingTo("-412.66");
        assertThat(jdbc.queryForObject("select sync_cursor from item", String.class)).isEqualTo("c1");
        assertThat(jdbc.queryForObject("select status from item", String.class)).isEqualTo("ok");
    }

    @Test
    void linkingARealInstitutionRemovesSampleData() throws Exception {
        sampleData.load(java.nio.file.Path.of("../reference/sample-data"));
        assertThat(jdbc.queryForObject("select count(*) from account where is_sample", Integer.class)).isEqualTo(12);
        link(bank("public-real"), "public-real");
        assertThat(jdbc.queryForObject("select count(*) from account where is_sample", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from budget_version where is_sample", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from account", Integer.class)).isEqualTo(2);
    }

    @Test
    void accessTokenIsEncryptedAtRest() {
        FakeItem item = bank("public-enc");
        link(item, "public-enc");
        byte[] stored = jdbc.queryForObject("select access_token_enc from item", byte[].class);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(item.accessToken);
        assertThat(stored.length).isGreaterThan(item.accessToken.length());
    }

    @Test
    void secondSyncIsIncrementalFromStoredCursor() {
        FakeItem item = bank("public-2");
        item.syncScript.add(page(List.of(txn("t1", "chk-0001", "2026-10-01", "10.00", "A")), List.of(), List.of(), "c1", false));
        UUID id = link(item, "public-2");

        item.syncScript.add(page(List.of(txn("t2", "chk-0001", "2026-10-03", "20.00", "B")), List.of(), List.of(), "c2", false));
        SyncResult second = sync.syncItem(id);

        assertThat(item.cursorsSeen).containsExactly(null, "c1"); // first call has no cursor, second resumes
        assertThat(second.added()).isEqualTo(1);
        assertThat(txnCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select sync_cursor from item", String.class)).isEqualTo("c2");
    }

    @Test
    void modifiedUpdatesPlaidFieldsButKeepsUserCategory() {
        FakeItem item = bank("public-3");
        item.syncScript.add(page(List.of(txn("t1", "chk-0001", "2026-10-01", "10.00", "Target"),
                txn("t2", "chk-0001", "2026-10-01", "5.00", "Starbucks")), List.of(), List.of(), "c1", false));
        UUID id = link(item, "public-3");
        jdbc.update("""
                update transaction set category = 'Lifestyle', subcategory = 'Gifts', category_source = 'user', reviewed = true
                where plaid_transaction_id = 't1'""");
        jdbc.update("""
                update transaction set category = 'Food', subcategory = 'Coffee', category_source = 'plaid', confidence = 0.9
                where plaid_transaction_id = 't2'""");

        item.syncScript.add(page(List.of(), List.of(
                txn("t1", "chk-0001", "2026-10-02", "12.50", "Target Store"),
                txn("t2", "chk-0001", "2026-10-02", "5.75", "Starbucks")), List.of(), "c2", false));
        assertThat(sync.syncItem(id).modified()).isEqualTo(2);

        Map<String, Object> t1 = jdbc.queryForMap("select * from transaction where plaid_transaction_id = 't1'");
        assertThat((BigDecimal) t1.get("amount")).isEqualByComparingTo("-12.50");
        assertThat(t1.get("merchant_name")).isEqualTo("Target Store");
        assertThat(t1.get("date").toString()).isEqualTo("2026-10-02");
        assertThat(t1.get("category")).isEqualTo("Lifestyle");
        assertThat(t1.get("subcategory")).isEqualTo("Gifts");
        assertThat(t1.get("category_source")).isEqualTo("user");
        assertThat(t1.get("reviewed")).isEqualTo(true);
        assertThat(amount("t2")).isEqualByComparingTo("-5.75");
    }

    @Test
    void removedTransactionsAreDeleted() {
        FakeItem item = bank("public-4");
        item.syncScript.add(page(List.of(txn("t1", "chk-0001", "2026-10-01", "10.00", "A"),
                txn("t2", "chk-0001", "2026-10-01", "20.00", "B")), List.of(), List.of(), "c1", false));
        UUID id = link(item, "public-4");

        item.syncScript.add(page(List.of(), List.of(), List.of("t1"), "c2", false));
        assertThat(sync.syncItem(id).removed()).isEqualTo(1);

        assertThat(jdbc.queryForList("select plaid_transaction_id from transaction", String.class)).containsExactly("t2");
    }

    @Test
    void pendingToPostedKeepsTheUsersCategory() {
        FakeItem item = bank("public-5");
        PlaidGateway.Txn p = pending("p1", "cc-0002", "2026-10-05", "64.20", "Amazon");
        item.syncScript.add(page(List.of(p), List.of(), List.of(), "c1", false));
        UUID id = link(item, "public-5");
        // Tyler recategorizes the pending charge (Phase 2's PATCH does exactly this).
        jdbc.update("""
                update transaction set category = 'Side Hustles', subcategory = 'Reselling Inventory',
                       category_source = 'user', reviewed = true, confidence = 1
                where plaid_transaction_id = 'p1'""");

        // It settles: Plaid adds the posted transaction (pointing at the pending one) and removes the pending.
        item.syncScript.add(page(List.of(posted(p, "t9", "p1")), List.of(), List.of("p1"), "c2", false));
        SyncResult r = sync.syncItem(id);

        assertThat(r.added()).isEqualTo(1);
        assertThat(r.removed()).isEqualTo(1);
        Map<String, Object> t = jdbc.queryForMap("select * from transaction");
        assertThat(t.get("plaid_transaction_id")).isEqualTo("t9");
        assertThat(t.get("pending_plaid_transaction_id")).isEqualTo("p1");
        assertThat(t.get("pending")).isEqualTo(false);
        assertThat(t.get("category")).isEqualTo("Side Hustles");
        assertThat(t.get("subcategory")).isEqualTo("Reselling Inventory");
        assertThat(t.get("category_source")).isEqualTo("user");
        assertThat(t.get("reviewed")).isEqualTo(true);
    }

    @Test
    void pendingToPostedAcrossPagesOfOneSync() {
        FakeItem item = bank("public-5b");
        PlaidGateway.Txn p = pending("p1", "cc-0002", "2026-10-05", "9.99", "Netflix");
        item.syncScript.add(page(List.of(p), List.of(), List.of(), "c1", false));
        UUID id = link(item, "public-5b");
        jdbc.update("update transaction set category = 'Subscriptions', subcategory = 'Streaming', category_source = 'user', reviewed = true");

        // Removal of the pending row arrives on page 1, the posted add on page 2.
        item.syncScript.add(page(List.of(), List.of(), List.of("p1"), "c2", true));
        item.syncScript.add(page(List.of(posted(p, "t1", "p1")), List.of(), List.of(), "c3", false));
        sync.syncItem(id);

        assertThat(jdbc.queryForMap("select plaid_transaction_id, category, subcategory from transaction"))
                .containsEntry("plaid_transaction_id", "t1").containsEntry("category", "Subscriptions")
                .containsEntry("subcategory", "Streaming");
    }

    @Test
    void multiPageSyncIsAllOrNothing() {
        FakeItem item = bank("public-6");
        item.syncScript.add(page(List.of(txn("t1", "chk-0001", "2026-10-01", "10.00", "A")), List.of(), List.of(), "c1", false));
        UUID id = link(item, "public-6");

        // Page 1 arrives, then Plaid fails on page 2.
        item.syncScript.add(page(List.of(txn("t2", "chk-0001", "2026-10-02", "20.00", "B")), List.of(), List.of(), "c2", true));
        item.syncScript.add(new PlaidApiException("API_ERROR", "INTERNAL_SERVER_ERROR", "boom", null, "req-500"));
        SyncResult r = sync.syncItem(id);

        assertThat(r.outcome()).isEqualTo("error");
        assertThat(r.errorCode()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(txnCount()).isEqualTo(1); // page 1 of the failed run was not written
        assertThat(jdbc.queryForObject("select sync_cursor from item", String.class)).isEqualTo("c1");
        assertThat(jdbc.queryForObject("select status from item", String.class)).isEqualTo("error");

        // Next run replays from c1 and succeeds; status recovers.
        item.syncScript.add(page(List.of(txn("t2", "chk-0001", "2026-10-02", "20.00", "B")), List.of(), List.of(), "c2", true));
        item.syncScript.add(page(List.of(txn("t3", "chk-0001", "2026-10-03", "30.00", "C")), List.of(), List.of(), "c3", false));
        assertThat(sync.syncItem(id).added()).isEqualTo(2);
        assertThat(item.cursorsSeen.subList(item.cursorsSeen.size() - 2, item.cursorsSeen.size())).containsExactly("c1", "c2");
        assertThat(jdbc.queryForObject("select sync_cursor from item", String.class)).isEqualTo("c3");
        assertThat(jdbc.queryForObject("select status from item", String.class)).isEqualTo("ok");
    }

    @Test
    void mutationDuringPaginationRestartsFromSavedCursor() {
        FakeItem item = bank("public-7");
        item.syncScript.add(page(List.of(txn("t1", "chk-0001", "2026-10-01", "10.00", "A")), List.of(), List.of(), "c1", true));
        item.syncScript.add(new PlaidApiException("TRANSACTIONS_ERROR", "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION",
                "data changed", null, "req-mut"));
        item.syncScript.add(page(List.of(txn("t1", "chk-0001", "2026-10-01", "10.00", "A")), List.of(), List.of(), "c1", true));
        item.syncScript.add(page(List.of(txn("t2", "chk-0001", "2026-10-02", "20.00", "B")), List.of(), List.of(), "c2", false));

        link(item, "public-7");

        assertThat(item.cursorsSeen).containsExactly(null, "c1", null, "c1");
        assertThat(txnCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select sync_cursor from item", String.class)).isEqualTo("c2");
    }

    @Test
    void loginRequiredIsSurfacedAndOtherItemsStillSync() {
        FakeItem broken = bank("public-8a");
        FakeItem fine = bank("public-8b");
        fine.accounts = List.of(account("sofi-0003", "SoFi Checking", "depository", "checking", "1386.10"));
        UUID brokenId = link(broken, "public-8a");
        UUID fineId = link(fine, "public-8b");

        broken.failWith = FakePlaidGateway.loginRequired();
        fine.syncScript.add(page(List.of(txn("t1", "sofi-0003", "2026-10-04", "7.00", "Coffee")), List.of(), List.of(), "c9", false));
        ResponseEntity<Map> res = http.postForEntity("/api/sync", null, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> results = (List<Map<String, Object>>) res.getBody().get("results");
        assertThat(results).extracting(r -> r.get("outcome")).containsExactlyInAnyOrder("login_required", "ok");
        assertThat(jdbc.queryForObject("select status from item where id = ?", String.class, brokenId)).isEqualTo("login_required");
        assertThat(jdbc.queryForObject("select last_error_code from item where id = ?", String.class, brokenId))
                .isEqualTo("ITEM_LOGIN_REQUIRED");
        assertThat(jdbc.queryForObject("select status from item where id = ?", String.class, fineId)).isEqualTo("ok");

        // GET /api/items shows it so the UI can offer Reconnect.
        List<Map<String, Object>> items = http.getForObject("/api/items", List.class);
        assertThat(items).anySatisfy(i -> assertThat(i).containsEntry("status", "login_required"));
        assertThat(items.toString()).doesNotContain("access-sandbox");

        // Reconnect: update-mode link token for that Item (its access token, no products).
        ResponseEntity<Map> tok = http.postForEntity("/api/plaid/link-token", Map.of("itemId", brokenId.toString()), Map.class);
        assertThat(tok.getStatusCode()).isEqualTo(HttpStatus.OK);
        PlaidGateway.LinkTokenSpec spec = plaid.linkSpecs.get(plaid.linkSpecs.size() - 1);
        assertThat(spec.accessToken()).isEqualTo(broken.accessToken);
        assertThat(spec.products()).isEmpty();

        // After Link succeeds in update mode, the UI calls Sync now; status goes back to ok.
        broken.failWith = null;
        http.postForEntity("/api/sync", null, Map.class);
        assertThat(jdbc.queryForObject("select status from item where id = ?", String.class, brokenId)).isEqualTo("ok");
    }

    @Test
    void newLinkTokenRequestsTransactionsWithTwoYearsOrInvestmentsForRetirement() {
        http.postForEntity("/api/plaid/link-token", Map.of(), Map.class);
        http.postForEntity("/api/plaid/link-token", Map.of("kind", "retirement"), Map.class);
        assertThat(plaid.linkSpecs.get(0).products()).containsExactly("transactions");
        assertThat(plaid.linkSpecs.get(0).daysRequested()).isEqualTo(730);
        assertThat(plaid.linkSpecs.get(0).accessToken()).isNull();
        assertThat(plaid.linkSpecs.get(1).products()).containsExactly("investments");

        assertThat(http.postForEntity("/api/plaid/link-token", Map.of("kind", "crypto"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/api/plaid/link-token", Map.of("itemId", UUID.randomUUID().toString()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void retirementItemSkipsTransactionsAndFallsBackToHoldings() {
        FakeItem item = plaid.prepare("public-9");
        item.institutionName = "Empower";
        item.products = List.of("investments");
        item.accounts = List.of(account("k401-0004", "401(k)", "investment", "401k", null));
        item.holdings = Map.of("k401-0004", new BigDecimal("61377.00"));

        link(item, "public-9");

        assertThat(item.cursorsSeen).isEmpty(); // never called /transactions/sync
        assertThat(jdbc.queryForObject("select role from account", String.class)).isEqualTo("balance_only");
        assertThat(jdbc.queryForObject("select current from balance_snapshot", BigDecimal.class)).isEqualByComparingTo("61377.00");
    }

    @Test
    void balancesUpsertOncePerDayAndKeepUserAccountName() {
        FakeItem item = bank("public-10");
        UUID id = link(item, "public-10");
        jdbc.update("update account set name = 'Bills checking' where plaid_account_id = 'chk-0001'");

        item.accounts = List.of(account("chk-0001", "Plaid Checking", "depository", "checking", "4000.00"),
                account("cc-0002", "Plaid Credit Card", "credit", "credit card", "500.00"));
        sync.syncItem(id);

        assertThat(jdbc.queryForObject("select count(*) from balance_snapshot", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select b.current from balance_snapshot b join account a on a.id = b.account_id
                where a.plaid_account_id = 'chk-0001'""", BigDecimal.class)).isEqualByComparingTo("4000.00");
        assertThat(jdbc.queryForObject("select name from account where plaid_account_id = 'chk-0001'", String.class))
                .isEqualTo("Bills checking");
    }

    @Test
    void oneBrokenItemNeverStopsTheScheduledSync() {
        FakeItem fine = bank("public-11");
        link(fine, "public-11");
        // An Item whose stored token can't be decrypted (e.g. TOKEN_ENC_KEY was changed).
        jdbc.update("insert into item (plaid_item_id, access_token_enc, products) values ('item-bad', ?, '{transactions}')",
                new byte[] {1, 2, 3});
        fine.syncScript.add(page(List.of(txn("t1", "chk-0001", "2026-10-04", "7.00", "Coffee")), List.of(), List.of(), "c2", false));

        scheduler.runAll(); // must not throw

        assertThat(amount("t1")).isEqualByComparingTo("-7.00");
        assertThat(sync.syncAll()).extracting(SyncResult::outcome).containsExactlyInAnyOrder("ok", "error");
    }

    @Test
    void exchangeErrorsComeBackAsProblemDetailsWithRequestId() {
        ResponseEntity<Map> res = http.postForEntity("/api/plaid/exchange", Map.of("publicToken", "nope"), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(res.getBody()).containsEntry("errorCode", "INVALID_PUBLIC_TOKEN").containsEntry("requestId", "req-x");
        assertThat(jdbc.queryForObject("select count(*) from item", Integer.class)).isZero();
    }
}
