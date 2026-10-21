package com.tylerknapp.finance.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tylerknapp.finance.PostgresTestBase;
import com.tylerknapp.finance.sample.SampleDataService;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ledger API against the kit's sample data. Expected counts are computed straight from the JSON
 * files with the prototype's filter rules (renderLedger), not from the database.
 */
class LedgerApiTest extends PostgresTestBase {
    static final Path SAMPLE = Path.of("../reference/sample-data");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SampleDataService sample;
    @Autowired ObjectMapper json;

    List<JsonNode> fileTxns = new ArrayList<>();

    @BeforeEach
    void load() throws Exception {
        jdbc.execute("truncate item, account, transaction, balance_snapshot, budget_version cascade");
        assertThat(sample.load(SAMPLE).outcome()).isEqualTo("loaded");
        fileTxns.clear();
        for (File f : SAMPLE.toFile().listFiles((d, n) -> n.matches("\\d{4}-\\d{2}\\.json"))) {
            json.readTree(f).path("transactions").forEach(fileTxns::add);
        }
    }

    /** Prototype rule: transfers hidden unless shown or the Transfer category is picked. */
    long expected(String from, String to, Predicate<JsonNode> extra, boolean showTransfers, String category) {
        return fileTxns.stream()
                .filter(t -> t.path("date").asText().substring(0, 7).compareTo(from) >= 0
                        && t.path("date").asText().substring(0, 7).compareTo(to) <= 0)
                .filter(t -> category == null || category.equals(t.path("category").asText()))
                .filter(t -> showTransfers || !"Transfer".equals(t.path("category").asText()) || "Transfer".equals(category))
                .filter(extra).count();
    }

    Map<String, Object> get(String query) {
        ResponseEntity<Map> res = http.getForEntity("/api/transactions?" + query, Map.class);
        assertThat(res.getStatusCode()).as(query).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("transactions");
    }

    @Test
    void sampleDataLoadsOnceAndIsTagged() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from transaction", Integer.class)).isEqualTo(fileTxns.size());
        assertThat(fileTxns).hasSize(518);
        assertThat(jdbc.queryForObject("select count(*) from account where is_sample", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("select count(*) from budget_version where is_sample", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from balance_snapshot", Integer.class)).isEqualTo(12);
        // App sign is kept as-is from the files: rent is money out.
        assertThat(jdbc.queryForObject("""
                select amount from transaction where merchant_name = 'Polaris Pointe Apartments' and date = '2026-10-01'""",
                BigDecimal.class)).isEqualByComparingTo("-1640");
        assertThat(jdbc.queryForObject("select is_manual from account where name like 'Fidelity%'", Boolean.class)).isTrue();
        assertThat(sample.load(SAMPLE).outcome()).isEqualTo("already_loaded");
        assertThat(jdbc.queryForObject("select count(*) from transaction", Integer.class)).isEqualTo(518);
    }

    @Test
    void sampleDataNeverLoadsNextToRealAccountsAndClearsCompletely() throws Exception {
        sample.clear();
        assertThat(jdbc.queryForObject("select count(*) from account", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from transaction", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from budget_version", Integer.class)).isZero();
        jdbc.update("insert into account (name, role, is_manual) values ('Real manual', 'balance_only', true)");
        assertThat(sample.load(SAMPLE).outcome()).isEqualTo("skipped_real_data");
        assertThat(jdbc.queryForObject("select count(*) from transaction", Integer.class)).isZero();
    }

    @Test
    void monthViewMatchesPrototypeDefaultFilters() {
        Map<String, Object> page = get("from=2026-10&to=2026-10");
        assertThat(page.get("total")).isEqualTo((int) expected("2026-10", "2026-10", t -> true, false, null));
        assertThat(rows(page)).allSatisfy(r -> assertThat(r.get("category")).isNotEqualTo("Transfer"));
        Map<String, Object> first = rows(page).get(0);
        assertThat(first).containsKeys("merchant", "accountName", "accountColor", "amount", "needsReview", "subcategory");
        assertThat(first.toString()).doesNotContain("access");
    }

    @Test
    void transfersShowWhenToggledOrWhenTransferCategoryIsPicked() {
        long all = expected("2026-01", "2026-10", t -> true, true, null);
        assertThat(get("from=2026-01&to=2026-10&includeTransfers=true").get("total")).isEqualTo((int) all);
        long xfer = expected("2026-01", "2026-10", t -> true, false, "Transfer");
        assertThat(xfer).isPositive();
        assertThat(get("from=2026-01&to=2026-10&category=Transfer").get("total")).isEqualTo((int) xfer);
    }

    @Test
    void needsReviewMatchesLowConfidenceUnedited() {
        long exp = expected("2026-01", "2026-10",
                t -> t.path("confidence").asDouble() < 0.7 && !t.path("edited").asBoolean(), false, null);
        assertThat(exp).isPositive();
        Map<String, Object> page = get("from=2026-01&to=2026-10&needsReview=true&limit=500");
        assertThat(page.get("total")).isEqualTo((int) exp);
        assertThat(rows(page)).allSatisfy(r -> assertThat(r.get("needsReview")).isEqualTo(true));
    }

    @Test
    void searchAccountAndCategoryFilters() {
        long kroger = expected("2026-01", "2026-10",
                t -> t.path("merchant").asText().toLowerCase().contains("kroger"), false, null);
        assertThat(get("from=2026-01&to=2026-10&q=KROGER").get("total")).isEqualTo((int) kroger);
        assertThat(get("from=2026-01&to=2026-10&q=%25").get("total")).isEqualTo(0); // % is literal, not a wildcard

        String amexId = jdbc.queryForObject("select id::text from account where name = 'American Express'", String.class);
        long amex = expected("2026-01", "2026-10", t -> "amex".equals(t.path("account").asText()), false, null);
        assertThat(get("from=2026-01&to=2026-10&account=" + amexId).get("total")).isEqualTo((int) amex);

        long food = expected("2026-01", "2026-10", t -> true, false, "Food");
        assertThat(get("from=2026-01&to=2026-10&category=Food").get("total")).isEqualTo((int) food);
    }

    @Test
    void cursorPagingWalksEveryRowOnceNewestFirst() {
        int total = (int) expected("2026-01", "2026-10", t -> true, true, null);
        Set<Object> seen = new HashSet<>();
        String prevDate = "9999-99-99";
        String cursor = null;
        int pages = 0;
        do {
            Map<String, Object> page = get("from=2026-01&to=2026-10&includeTransfers=true"
                    + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.get("total")).isEqualTo(total);
            assertThat(rows(page).size()).isLessThanOrEqualTo(150);
            for (Map<String, Object> r : rows(page)) {
                assertThat(seen.add(r.get("id"))).as("duplicate row").isTrue();
                assertThat((String) r.get("date")).isLessThanOrEqualTo(prevDate);
                prevDate = (String) r.get("date");
            }
            cursor = (String) page.get("nextCursor");
            pages++;
        } while (cursor != null);
        assertThat(seen).hasSize(total);
        assertThat(pages).isEqualTo((total + 149) / 150);
    }

    @Test
    void rowsWithinADayKeepFileOrderLikeThePrototype() {
        List<String> fileOrder = fileTxns.stream().filter(t -> "2026-10-20".equals(t.path("date").asText()))
                .map(t -> t.path("merchant").asText()).toList();
        List<Object> apiOrder = rows(get("from=2026-10&to=2026-10&includeTransfers=true")).stream()
                .filter(r -> "2026-10-20".equals(r.get("date"))).map(r -> r.get("merchant")).toList();
        assertThat(apiOrder).containsExactlyElementsOf(fileOrder);
    }

    @Test
    void recategorizePersistsAsUserReviewed() {
        Map<String, Object> target = rows(get("from=2026-01&to=2026-10&needsReview=true")).get(0);
        int reviewBefore = (int) get("from=2026-01&to=2026-10&needsReview=true").get("total");

        ResponseEntity<Map> res = http.exchange("/api/transactions/" + target.get("id"), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("category", "Side Hustles", "subcategory", "Reselling Inventory")), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("category", "Side Hustles").containsEntry("categorySource", "user")
                .containsEntry("reviewed", true).containsEntry("needsReview", false);
        Map<String, Object> row = jdbc.queryForMap("select * from transaction where id = ?::uuid", target.get("id"));
        assertThat(row).containsEntry("subcategory", "Reselling Inventory").containsEntry("category_source", "user")
                .containsEntry("reviewed", true);
        assertThat(get("from=2026-01&to=2026-10&needsReview=true").get("total")).isEqualTo(reviewBefore - 1);
    }

    @Test
    void recategorizeRejectsPairsOutsideTheTaxonomyAndUnknownIds() {
        String id = (String) rows(get("from=2026-10&to=2026-10")).get(0).get("id");
        ResponseEntity<Map> bad = http.exchange("/api/transactions/" + id, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("category", "Food", "subcategory", "Gas")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> missing = http.exchange("/api/transactions/" + java.util.UUID.randomUUID(), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("category", "Food", "subcategory", "Coffee")), Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> empty = http.exchange("/api/transactions/" + id, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("category", "")), Map.class);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void hiddenAccountsDropOutOfTheLedger() {
        int before = (int) get("from=2026-01&to=2026-10").get("total");
        jdbc.update("update account set hidden = true where name = 'Capital One'");
        long c1 = expected("2026-01", "2026-10", t -> "c1".equals(t.path("account").asText()), false, null);
        assertThat(get("from=2026-01&to=2026-10").get("total")).isEqualTo(before - (int) c1);
    }

    @Test
    void validatesMonthsAndAcceptsReversedRange() {
        assertThat(http.getForEntity("/api/transactions?from=2026-13&to=2026-10", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/transactions?from=2026-10&to=2026-10&cursor=junk", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("from=2026-10&to=2026-08").get("total")).isEqualTo(get("from=2026-08&to=2026-10").get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void categoriesAndAccountsEndpoints() {
        List<Map<String, Object>> cats = http.getForObject("/api/categories", List.class);
        assertThat(cats).extracting(c -> c.get("name")).containsExactly("Housing", "Food", "Transportation",
                "Health & Fitness", "Subscriptions", "Lifestyle", "Side Hustles", "Income", "Transfer");
        assertThat((List<String>) cats.get(1).get("subcategories")).containsExactly("Groceries", "Restaurants", "Coffee");

        List<Map<String, Object>> accts = http.getForObject("/api/accounts", List.class);
        assertThat(accts).hasSize(12).allSatisfy(a -> assertThat(a.get("isSample")).isEqualTo(true));
        assertThat(accts).filteredOn(a -> "ledger".equals(a.get("role"))).hasSize(9);
        assertThat(accts).anySatisfy(a -> assertThat(a).containsEntry("name", "Huntington Voice Rewards")
                .containsEntry("displayColor", "#2f7d4f"));
    }
}
