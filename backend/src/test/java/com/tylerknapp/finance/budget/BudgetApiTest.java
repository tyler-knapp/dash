package com.tylerknapp.finance.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.tylerknapp.finance.FixedClockConfig;
import com.tylerknapp.finance.PostgresTestBase;
import com.tylerknapp.finance.sample.SampleDataService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(FixedClockConfig.class)
class BudgetApiTest extends PostgresTestBase {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SampleDataService sample;

    @BeforeEach
    void load() throws Exception {
        jdbc.execute("truncate item, account, transaction, balance_snapshot, budget_version cascade");
        sample.load(Path.of("../reference/sample-data"));
    }

    ResponseEntity<Map> put(String month, String category, Object amount) {
        return http.exchange("/api/budgets/" + month + "/" + category, HttpMethod.PUT,
                new HttpEntity<>(Map.of("amount", amount)), Map.class);
    }

    @SuppressWarnings("unchecked")
    BigDecimal budgetIn(String month, String category) {
        Map<String, Object> s = http.getForObject("/api/summary?from=" + month + "&to=" + month, Map.class);
        return ((List<Map<String, Object>>) s.get("categories")).stream().filter(c -> category.equals(c.get("name")))
                .map(c -> new BigDecimal(c.get("budget").toString())).findFirst().orElseThrow();
    }

    @Test
    void editingOctoberThroughTheApiLeavesSeptemberAlone() {
        assertThat(put("2026-10", "Food", 900).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(budgetIn("2026-10", "Food")).isEqualByComparingTo("900");
        assertThat(budgetIn("2026-09", "Food")).isEqualByComparingTo("750");
        assertThat(budgetIn("2026-06", "Food")).isEqualByComparingTo("700");
        assertThat(budgetIn("2026-10", "Housing")).isEqualByComparingTo("1900");
        List<Map<String, Object>> versions = http.getForObject("/api/budgets", List.class);
        assertThat(versions).extracting(v -> v.get("effectiveFrom")).containsExactly("2026-01", "2026-07", "2026-10");

        // Second edit to October updates the same version.
        put("2026-10", "Lifestyle", 425.5);
        assertThat(http.getForObject("/api/budgets", List.class)).hasSize(3);
        assertThat(budgetIn("2026-10", "Lifestyle")).isEqualByComparingTo("425.5");
        assertThat(budgetIn("2026-10", "Food")).isEqualByComparingTo("900");
    }

    @Test
    void anEditedVersionIsNoLongerSampleData() {
        put("2026-10", "Food", 900);
        assertThat(jdbc.queryForObject("select is_sample from budget_version where effective_from = '2026-10'", Boolean.class)).isFalse();
        sample.clear(); // what linking a real bank does
        assertThat(jdbc.queryForList("select effective_from from budget_version", String.class)).containsExactly("2026-10");
    }

    @Test
    void rejectsBadInput() {
        assertThat(put("2026-13", "Food", 1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("2026-10", "Income", 1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("2026-10", "Transfer", 1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("2026-10", "Nope", 1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put("2026-10", "Food", -5).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("2026-10", "Food", 1.234).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange("/api/budgets/2026-10/Food", HttpMethod.PUT, new HttpEntity<>(Map.of()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForObject("/api/budgets", List.class)).hasSize(2); // nothing changed
    }

    @Test
    void categoryNamesWithSpacesAndAmpersandsWork() {
        assertThat(put("2026-10", "Health & Fitness", 300).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(budgetIn("2026-10", "Health & Fitness")).isEqualByComparingTo("300");
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryCountsOnlySpendAndExcludesTransfersAndHiddenAccounts() {
        Map<String, Object> before = http.getForObject("/api/summary?from=2026-10&to=2026-10", Map.class);
        assertThat(before.get("periodState")).isEqualTo("current");
        assertThat(before.get("today")).isEqualTo("2026-10-21");
        assertThat(((List<?>) before.get("categories"))).hasSize(7); // spend categories only

        // Hide the Amex card: its spending drops out of the summary, as it does from the ledger.
        BigDecimal amexSpend = jdbc.queryForObject("""
                select coalesce(-sum(t.amount), 0) from transaction t join account a on a.id = t.account_id
                join category c on c.name = t.category
                where a.name = 'American Express' and c.kind = 'spend' and t.date >= '2026-10-01'""", BigDecimal.class);
        jdbc.update("update account set hidden = true where name = 'American Express'");
        Map<String, Object> after = http.getForObject("/api/summary?from=2026-10&to=2026-10", Map.class);
        assertThat(new BigDecimal(after.get("spent").toString()))
                .isEqualByComparingTo(new BigDecimal(before.get("spent").toString()).subtract(amexSpend));

        // Future periods: nothing spent, not started.
        Map<String, Object> future = http.getForObject("/api/summary?from=2026-11&to=2026-12", Map.class);
        assertThat(future.get("periodState")).isEqualTo("future");
        assertThat((List<Map<String, Object>>) future.get("categories")).allSatisfy(c -> assertThat(c.get("statusLabel")).isEqualTo("Not started"));
    }

    @Test
    void uncategorizedTransactionsAreCountedButNotSpent() {
        String acct = jdbc.queryForObject("select id::text from account where name = 'Capital One'", String.class);
        jdbc.update("insert into transaction (account_id, date, amount, merchant_name) values (?::uuid, '2026-10-05', -50, 'Mystery')", acct);
        Map<String, Object> s = http.getForObject("/api/summary?from=2026-10&to=2026-10", Map.class);
        assertThat(s.get("uncategorizedCount")).isEqualTo(1);
        assertThat(s.get("transactionCount")).isEqualTo(49);
        assertThat(s.get("spent").toString()).isEqualTo("3365.02"); // unchanged from the prototype's figure
    }
}
