package com.tylerknapp.finance.categorize;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tylerknapp.finance.FixedClockConfig;
import com.tylerknapp.finance.PostgresTestBase;
import com.tylerknapp.finance.plaid.FakePlaidGateway;
import com.tylerknapp.finance.plaid.PlaidGateway;
import com.tylerknapp.finance.sync.SyncService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SPEC §7 end to end: Plaid (faked) → sync → categorization pipeline → Postgres → API.
 * Covers the Phase 4 done-when: every transaction gets a valid category, invalid model output
 * falls back safely, and card payments never count as spending.
 */
@Import({CategorizationIntegrationTest.Fakes.class, FixedClockConfig.class})
class CategorizationIntegrationTest extends PostgresTestBase {

    @TestConfiguration
    static class Fakes {
        @Bean @Primary FakePlaidGateway fakePlaid() { return new FakePlaidGateway(); }
        @Bean @Primary FakeAi fakeAi() { return new FakeAi(); }
    }

    @Autowired FakePlaidGateway plaid;
    @Autowired FakeAi ai;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SyncService sync;
    @Autowired ObjectMapper json;
    @Autowired TaxonomyRepository taxonomyRepo;

    JsonNode fixture;
    Taxonomy tax;

    @BeforeEach
    void setUp() throws Exception {
        plaid.reset();
        ai.reset();
        jdbc.execute("truncate item, account, transaction, balance_snapshot, budget_version, category_rule cascade");
        fixture = json.readTree(getClass().getResourceAsStream("/categorize/sandbox-like.json"));
        tax = taxonomyRepo.load();
    }

    FakePlaidGateway.FakeItem prepareSandboxItem(String publicToken) {
        FakePlaidGateway.FakeItem item = plaid.prepare(publicToken);
        List<PlaidGateway.Account> accounts = new ArrayList<>();
        fixture.path("accounts").forEach(a -> accounts.add(FakePlaidGateway.account(a.path("id").asText(), a.path("name").asText(),
                a.path("type").asText(), a.path("subtype").asText(), a.path("current").asText())));
        item.accounts = accounts;
        List<PlaidGateway.Txn> txns = new ArrayList<>();
        fixture.path("transactions").forEach(t -> txns.add(txn(t)));
        item.syncScript.add(FakePlaidGateway.page(txns, List.of(), List.of(), "c1", false));
        return item;
    }

    static PlaidGateway.Txn txn(JsonNode t) {
        JsonNode pfc = t.path("pfc");
        return new PlaidGateway.Txn(t.path("id").asText(), t.path("account").asText(), null, LocalDate.parse(t.path("date").asText()),
                null, t.path("merchant").isNull() ? null : t.path("merchant").asText(), t.path("name").asText(),
                new BigDecimal(t.path("plaidAmount").asText()), false,
                pfc.isMissingNode() ? null : pfc.get(0).asText(), pfc.isMissingNode() ? null : pfc.get(1).asText(),
                pfc.isMissingNode() ? null : pfc.get(2).asText());
    }

    void link(String publicToken) {
        http.postForEntity("/api/plaid/exchange", Map.of("publicToken", publicToken), Map.class);
    }

    Map<String, Object> row(String plaidId) {
        return jdbc.queryForMap("select * from transaction where plaid_transaction_id = ?", plaidId);
    }

    String cat(String plaidId) {
        Map<String, Object> r = row(plaidId);
        return r.get("category") + " › " + r.get("subcategory");
    }

    void assertEveryTransactionHasAValidCategory() {
        List<Map<String, Object>> rows = jdbc.queryForList("select plaid_transaction_id, category, subcategory, confidence from transaction");
        assertThat(rows).hasSize(fixture.path("transactions").size());
        assertThat(rows).allSatisfy(r -> {
            assertThat(tax.isValid((String) r.get("category"), (String) r.get("subcategory"))).as(r.toString()).isTrue();
            assertThat((BigDecimal) r.get("confidence")).as(r.toString()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        });
    }

    /** Card payments, both sides, are Transfer › Card Payment and drop out of Spent. */
    @SuppressWarnings("unchecked")
    void assertCardPaymentsNeverCountAsSpending() {
        fixture.path("transactions").forEach(t -> {
            if (t.path("cardPayment").asBoolean()) {
                assertThat(cat(t.path("id").asText())).as(t.path("name").asText()).isEqualTo("Transfer › Card Payment");
            }
        });
        BigDecimal spendFromDb = jdbc.queryForObject("""
                select coalesce(-sum(t.amount), 0) from transaction t join category c on c.name = t.category
                where c.kind = 'spend'""", BigDecimal.class);
        Map<String, Object> s = http.getForObject("/api/summary?from=2026-10&to=2026-10", Map.class);
        assertThat(new BigDecimal(s.get("spent").toString())).isEqualByComparingTo(spendFromDb);
        BigDecimal cardPaymentTotal = jdbc.queryForObject(
                "select sum(abs(amount)) from transaction where subcategory = 'Card Payment'", BigDecimal.class);
        assertThat(cardPaymentTotal).isEqualByComparingTo("2928.82"); // 2078.50 + 25 + 412.66 x 2, none in Spent
    }

    @Test
    void withoutAModelEveryTransactionStillGetsAValidCategory() {
        prepareSandboxItem("public-a");
        link("public-a");

        assertEveryTransactionHasAValidCategory();
        assertCardPaymentsNeverCountAsSpending();
        assertThat(ai.batches).isEmpty();
        // Confident clear cases come straight from Plaid.
        assertThat(cat("s07")).isEqualTo("Income › Salary");
        assertThat(cat("s16")).isEqualTo("Food › Groceries");
        assertThat(cat("s12")).isEqualTo("Food › Coffee");
        assertThat(cat("s10")).isEqualTo("Lifestyle › Travel"); // a refund, positive, in the purchase's category
        assertThat(row("s16")).containsEntry("category_source", "plaid");
        // Transfers: Plaid's, and a pair with no transfer signal except the matching amounts.
        assertThat(cat("s19")).isEqualTo("Transfer › Account Transfer");
        assertThat(cat("s20")).isEqualTo("Transfer › Account Transfer");
        assertThat(cat("s23")).isEqualTo("Transfer › Account Transfer");
        assertThat(cat("s24")).isEqualTo("Transfer › Account Transfer");
        assertThat(row("s23")).containsEntry("category_source", "rule");
        // Venmo isn't treated as a transfer; it gets a low-confidence guess for review.
        assertThat(cat("s15")).isEqualTo("Lifestyle › Gifts");
        // Unclear ones are low confidence, so the ledger shows them as needing review.
        assertThat((BigDecimal) row("s13").get("confidence")).isLessThan(new BigDecimal("0.7"));
        assertThat((BigDecimal) row("s17").get("confidence")).isLessThan(new BigDecimal("0.7"));
        Map<String, Object> page = http.getForObject("/api/transactions?from=2026-10&to=2026-10&needsReview=true&limit=500", Map.class);
        List<Map<String, Object>> review = (List<Map<String, Object>>) page.get("transactions");
        assertThat(review).extracting(r -> r.get("merchant")).contains("SparkFun", "Target", "Venmo");
        assertThat(review).extracting(r -> r.get("merchant")).doesNotContain("Kroger", "Starbucks");
    }

    @Test
    void theModelOnlySeesWhatEarlierStepsCouldNotSettle_andValidAnswersAreUsed() {
        ai.enabled = true;
        ai.respond = batch -> batch.stream().map(i -> new AiCategorizer.Answer(i.id(), "Lifestyle", "Events", 0.88)).toList();
        prepareSandboxItem("public-b");
        link("public-b");

        assertThat(ai.batches).hasSize(1);
        List<String> sent = ai.batches.get(0).stream().map(AiCategorizer.Input::rawName).toList();
        assertThat(sent).contains("Uber 072515 SF**POOL**", "Tectra Inc", "SparkFun", "Venmo payment", "TARGET 00012345", "AEP OHIO BILL PAY");
        assertThat(sent).doesNotContain("KROGER #512 POLARIS", "CREDIT CARD 3333 PAYMENT *//", "Transfer to savings", "WEB XFER 8834",
                "ACH Electronic CreditGUSTO PAY 123456");
        assertThat(ai.batches.get(0)).allSatisfy(i -> assertThat(i.id()).matches("t\\d+")); // short ids, no Plaid ids
        assertThat(row("s13")).containsEntry("category_source", "ai").containsEntry("category", "Lifestyle");
        assertThat((BigDecimal) row("s13").get("confidence")).isEqualByComparingTo("0.88");
        assertEveryTransactionHasAValidCategory();
        assertCardPaymentsNeverCountAsSpending();
        // Seeded examples for the spec's ambiguous merchants are always included.
        assertThat(ai.examplesSeen.get(0)).extracting(AiCategorizer.Example::merchant)
                .contains("Kroger Fuel #512", "Plato's Closet", "Goodwill Polaris", "Ticketmaster resale", "Amazon.com", "Target", "Venmo payment");
    }

    @Test
    void invalidModelOutputFallsBackSafely() {
        ai.enabled = true;
        ai.respond = batch -> {
            List<AiCategorizer.Answer> out = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                String id = batch.get(i).id();
                out.add(switch (i % 6) {
                    case 0 -> new AiCategorizer.Answer(id, "Shopping", "Misc", 0.9);          // not in taxonomy
                    case 1 -> new AiCategorizer.Answer(id, "Food", "Gas", 0.9);               // wrong pair
                    case 2 -> new AiCategorizer.Answer(id, "Food", "Coffee", 1.7);            // bad confidence
                    case 3 -> new AiCategorizer.Answer("t999", "Food", "Coffee", 0.9);        // unknown id
                    case 4 -> new AiCategorizer.Answer(id, null, null, null);                 // empty
                    default -> new AiCategorizer.Answer(id, "Health & Fitness", "Race Fees", 0.8); // valid
                });
            }
            if (!batch.isEmpty()) {
                out.add(new AiCategorizer.Answer(batch.get(0).id(), "Food", "Coffee", 0.99)); // duplicate of a bad one: ignored
            }
            return out;
        };
        prepareSandboxItem("public-c");
        link("public-c");

        assertEveryTransactionHasAValidCategory();
        assertCardPaymentsNeverCountAsSpending();
        List<Map<String, Object>> aiRows = jdbc.queryForList("select * from transaction where category_source = 'ai'");
        assertThat(aiRows).isNotEmpty().allSatisfy(r -> assertThat(r.get("subcategory")).isEqualTo("Race Fees"));
        // Every answer that was wrong became a Plaid-map fallback at confidence 0.3.
        int sent = ai.batches.get(0).size();
        long fallbacks = jdbc.queryForObject(
                "select count(*) from transaction where category_source = 'plaid' and confidence = 0.3", Long.class);
        assertThat(fallbacks).isEqualTo(sent - aiRows.size());
    }

    @Test
    void aFailedModelCallFallsBackForTheWholeBatch() {
        ai.enabled = true;
        ai.respond = batch -> { throw new RuntimeException("503 overloaded"); };
        prepareSandboxItem("public-d");
        link("public-d");

        assertEveryTransactionHasAValidCategory();
        assertCardPaymentsNeverCountAsSpending();
        assertThat(jdbc.queryForObject("select count(*) from transaction where category_source = 'ai'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select status from item", String.class)).isEqualTo("ok"); // sync still succeeded
    }

    @Test
    void largeSyncsGoToTheModelInBatchesOf50() {
        ai.enabled = true;
        ai.respond = batch -> batch.stream().map(i -> new AiCategorizer.Answer(i.id(), "Lifestyle", "Gifts", 0.5)).toList();
        FakePlaidGateway.FakeItem item = plaid.prepare("public-e");
        item.accounts = List.of(FakePlaidGateway.account("cc", "Plaid Credit Card", "credit", "credit card", "1"));
        List<PlaidGateway.Txn> many = IntStream.range(0, 120).mapToObj(i -> new PlaidGateway.Txn("m" + i, "cc", null,
                LocalDate.of(2026, 10, 1 + i % 20), null, "Shop " + i, "SHOP " + i, new BigDecimal("10." + (10 + i % 80)), false,
                "GENERAL_MERCHANDISE", "GENERAL_MERCHANDISE_OTHER_GENERAL_MERCHANDISE", "HIGH")).toList();
        item.syncScript.add(FakePlaidGateway.page(many, List.of(), List.of(), "c1", false));
        link("public-e");

        assertThat(ai.batches).extracting(List::size).containsExactly(50, 50, 20);
        assertThat(jdbc.queryForObject("select count(*) from transaction where category_source = 'ai'", Integer.class)).isEqualTo(120);
    }

    @Test
    void theModelSeesUpTo20OfTylersOwnCorrections() {
        prepareSandboxItem("public-f");
        link("public-f");
        // Tyler corrects 25 transactions through the API.
        List<String> ids = jdbc.queryForList("select id::text from transaction order by seq limit 24", String.class);
        for (String id : ids) {
            http.exchange("/api/transactions/" + id, HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("category", "Side Hustles", "subcategory", "YouTube Gear")), Map.class);
        }
        ai.enabled = true;
        ai.respond = batch -> batch.stream().map(i -> new AiCategorizer.Answer(i.id(), "Food", "Coffee", 0.9)).toList();
        FakePlaidGateway.FakeItem item = plaid.byAccessToken.values().iterator().next();
        item.syncScript.add(FakePlaidGateway.page(List.of(new PlaidGateway.Txn("n1", "cc", null, LocalDate.of(2026, 10, 15), null,
                "Corner Store", "CORNER STORE", new BigDecimal("3.50"), false, "GENERAL_MERCHANDISE",
                "GENERAL_MERCHANDISE_CONVENIENCE_STORES", "MEDIUM")), List.of(), List.of(), "c2", false));
        sync.syncItem(UUID.fromString(jdbc.queryForObject("select id::text from item", String.class)));

        List<AiCategorizer.Example> ex = ai.examplesSeen.get(0);
        long userExamples = ex.stream().filter(e -> "corrected by the user".equals(e.note())).count();
        assertThat(userExamples).isEqualTo(20);
        assertThat(ex).filteredOn(e -> "corrected by the user".equals(e.note()))
                .allSatisfy(e -> assertThat(e.category() + " › " + e.subcategory()).isEqualTo("Side Hustles › YouTube Gear"));
    }

    @Test
    void transferPairsAcrossSyncsUpdateTheEarlierSideButNeverAUserChoice() {
        FakePlaidGateway.FakeItem item = plaid.prepare("public-g");
        item.accounts = List.of(FakePlaidGateway.account("chk", "Checking", "depository", "checking", "1"),
                FakePlaidGateway.account("sav", "Savings", "depository", "savings", "1"));
        PlaidGateway.Txn out1 = new PlaidGateway.Txn("x1", "chk", null, LocalDate.of(2026, 10, 1), null, null, "ONLINE XFER 1",
                new BigDecimal("250.00"), false, "GENERAL_SERVICES", "GENERAL_SERVICES_OTHER_GENERAL_SERVICES", "LOW");
        PlaidGateway.Txn out2 = new PlaidGateway.Txn("x2", "chk", null, LocalDate.of(2026, 10, 1), null, null, "ONLINE XFER 2",
                new BigDecimal("75.00"), false, "GENERAL_SERVICES", "GENERAL_SERVICES_OTHER_GENERAL_SERVICES", "LOW");
        item.syncScript.add(FakePlaidGateway.page(List.of(out1, out2), List.of(), List.of(), "c1", false));
        link("public-g");
        assertThat(cat("x1")).isNotEqualTo("Transfer › Account Transfer"); // no other side yet
        jdbc.update("update transaction set category = 'Lifestyle', subcategory = 'Gifts', category_source = 'user', reviewed = true where plaid_transaction_id = 'x2'");

        // Two days later the money lands in savings.
        item.syncScript.add(FakePlaidGateway.page(List.of(
                new PlaidGateway.Txn("y1", "sav", null, LocalDate.of(2026, 10, 3), null, null, "DEPOSIT 1", new BigDecimal("-250.00"),
                        false, "GENERAL_SERVICES", "GENERAL_SERVICES_OTHER_GENERAL_SERVICES", "LOW"),
                new PlaidGateway.Txn("y2", "sav", null, LocalDate.of(2026, 10, 3), null, null, "DEPOSIT 2", new BigDecimal("-75.00"),
                        false, "GENERAL_SERVICES", "GENERAL_SERVICES_OTHER_GENERAL_SERVICES", "LOW")), List.of(), List.of(), "c2", false));
        sync.syncItem(UUID.fromString(jdbc.queryForObject("select id::text from item", String.class)));

        assertThat(cat("y1")).isEqualTo("Transfer › Account Transfer");
        assertThat(cat("x1")).isEqualTo("Transfer › Account Transfer"); // earlier side updated
        assertThat(cat("x2")).isEqualTo("Lifestyle › Gifts");           // Tyler's choice kept
        assertThat(row("x2")).containsEntry("category_source", "user");
    }

    @Test
    void coincidentalEqualAmountsDoNotPairWhenPlaidIsSureOfAPurchase() {
        FakePlaidGateway.FakeItem item = plaid.prepare("public-h");
        item.accounts = List.of(FakePlaidGateway.account("chk", "Checking", "depository", "checking", "1"),
                FakePlaidGateway.account("cc", "Card", "credit", "credit card", "1"));
        item.syncScript.add(FakePlaidGateway.page(List.of(
                new PlaidGateway.Txn("d1", "cc", null, LocalDate.of(2026, 10, 2), null, "Polaris Grill", "POLARIS GRILL", new BigDecimal("40.00"),
                        false, "FOOD_AND_DRINK", "FOOD_AND_DRINK_RESTAURANT", "VERY_HIGH"),
                new PlaidGateway.Txn("d2", "chk", null, LocalDate.of(2026, 10, 3), null, "Mercari", "MERCARI PAYOUT", new BigDecimal("-40.00"),
                        false, "INCOME", "INCOME_OTHER_INCOME", "HIGH")), List.of(), List.of(), "c1", false));
        link("public-h");
        assertThat(cat("d1")).isEqualTo("Food › Restaurants");
        assertThat(cat("d2")).isNotEqualTo("Transfer › Card Payment");
    }

    @Test
    void userRulesComeFirst() {
        jdbc.update("""
                insert into category_rule (priority, match_field, match_type, pattern, category, subcategory)
                values (1, 'merchant', 'contains', 'starbucks', 'Side Hustles', 'YouTube Gear')""");
        prepareSandboxItem("public-i");
        link("public-i");
        assertThat(cat("s12")).isEqualTo("Side Hustles › YouTube Gear");
        assertThat(row("s12")).containsEntry("category_source", "rule");
        assertThat((BigDecimal) row("s12").get("confidence")).isEqualByComparingTo("1");
    }

    @Test
    void recategorizeEndpointUpgradesFallbacksOnceAModelIsAvailable() {
        prepareSandboxItem("public-j");
        link("public-j"); // no model: unclear ones get 0.3 fallbacks
        long before = jdbc.queryForObject("select count(*) from transaction where category_source = 'plaid' and confidence = 0.3", Long.class);
        assertThat(before).isPositive();
        String reviewedId = jdbc.queryForObject(
                "select id::text from transaction where category_source = 'plaid' and confidence = 0.3 order by seq limit 1", String.class);
        jdbc.update("update transaction set reviewed = true where id = ?::uuid", reviewedId);

        ai.enabled = true;
        ai.respond = batch -> batch.stream().map(i -> new AiCategorizer.Answer(i.id(), "Lifestyle", "Events", 0.9)).toList();
        Map<String, Object> res = http.postForObject("/api/transactions/categorize", null, Map.class);

        assertThat(res.get("considered")).isEqualTo((int) before - 1);
        assertThat(jdbc.queryForObject("select count(*) from transaction where category_source = 'ai'", Long.class)).isEqualTo(before - 1);
        assertThat(jdbc.queryForObject("select category_source from transaction where id = ?::uuid", String.class, reviewedId)).isEqualTo("plaid");
    }

    @Test
    void transactionsWithNoPlaidCategoryAndNoModelStayUncategorizedAndNeedReview() {
        FakePlaidGateway.FakeItem item = plaid.prepare("public-k");
        item.accounts = List.of(FakePlaidGateway.account("cc", "Card", "credit", "credit card", "1"));
        item.syncScript.add(FakePlaidGateway.page(List.of(new PlaidGateway.Txn("u1", "cc", null, LocalDate.of(2026, 10, 2), null,
                null, "MYSTERY CO", new BigDecimal("15.00"), false, null, null, null)), List.of(), List.of(), "c1", false));
        link("public-k");
        assertThat(row("u1").get("category")).isNull();
        Map<String, Object> page = http.getForObject("/api/transactions?from=2026-10&to=2026-10&needsReview=true", Map.class);
        assertThat((Integer) page.get("total")).isEqualTo(1);
    }
}
