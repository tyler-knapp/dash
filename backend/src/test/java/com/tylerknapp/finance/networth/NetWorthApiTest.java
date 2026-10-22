package com.tylerknapp.finance.networth;

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

/** Phase 5 done-when: totals equal the sum of latest snapshots; a manual account shows its updated date. */
@Import(FixedClockConfig.class)
@SuppressWarnings("unchecked")
class NetWorthApiTest extends PostgresTestBase {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SampleDataService sample;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate item, account, transaction, balance_snapshot, budget_version cascade");
    }

    Map<String, Object> manual(String name, String type) {
        ResponseEntity<Map> res = http.postForEntity("/api/accounts/manual", Map.of("name", name, "type", type), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    ResponseEntity<Map> balance(Object id, String asOf, String current) {
        return http.postForEntity("/api/accounts/" + id + "/balance", Map.of("asOf", asOf, "current", current), Map.class);
    }

    ResponseEntity<Map> patch(Object id, Map<String, Object> body) {
        return http.exchange("/api/accounts/" + id, HttpMethod.PATCH, new HttpEntity<>(body), Map.class);
    }

    BigDecimal sumOfLatestSnapshotsInDb() {
        return jdbc.queryForObject("""
                select coalesce(sum(current), 0) from (
                  select distinct on (s.account_id) s.current from balance_snapshot s join account a on a.id = s.account_id
                  where not a.hidden order by s.account_id, s.as_of desc) latest""", BigDecimal.class);
    }

    @Test
    void totalsEqualTheSumOfLatestSnapshots() throws Exception {
        sample.load(Path.of("../reference/sample-data"));
        Map<String, Object> house = manual("Car (Kelley Blue Book)", "other");
        Map<String, Object> loan = manual("Car loan", "loan");
        balance(house.get("id"), "2026-06-01", "15000");
        balance(house.get("id"), "2026-10-01", "14200.50"); // newer value wins
        balance(loan.get("id"), "2026-09-15", "-9800");
        // Older snapshots for linked/sample accounts must not count.
        String hv = jdbc.queryForObject("select id::text from account where name = 'Huntington Voice Rewards'", String.class);
        jdbc.update("insert into balance_snapshot (account_id, as_of, current, source) values (?::uuid, '2026-09-01', -9999, 'plaid')", hv);
        manual("No balance yet", "depository"); // not in totals

        Map<String, Object> nw = http.getForObject("/api/networth", Map.class);

        BigDecimal total = new BigDecimal(nw.get("total").toString());
        assertThat(total).isEqualByComparingTo(sumOfLatestSnapshotsInDb());
        assertThat(total).isEqualByComparingTo(new BigDecimal("176961.01").add(new BigDecimal("14200.50")).subtract(new BigDecimal("9800")));
        BigDecimal groupSum = ((List<Map<String, Object>>) nw.get("groups")).stream()
                .map(g -> new BigDecimal(g.get("total").toString())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(groupSum).isEqualByComparingTo(total);
        BigDecimal retirement = ((List<Map<String, Object>>) nw.get("groups")).stream().filter(g -> "Retirement".equals(g.get("name")))
                .map(g -> new BigDecimal(g.get("total").toString())).findFirst().orElseThrow();
        assertThat(new BigDecimal(nw.get("cashMinusCards").toString())).isEqualByComparingTo(total.subtract(retirement));
        assertThat((List<Map<String, Object>>) nw.get("withoutBalance")).extracting(a -> a.get("name")).containsExactly("No balance yet");
        assertThat((List<Map<String, Object>>) nw.get("groups")).extracting(g -> g.get("name"))
                .containsExactly("Cash & savings", "Credit cards", "Retirement", "Other");

        // Hiding an account takes it out of the totals.
        patch(house.get("id"), Map.of("hidden", true));
        Map<String, Object> after = http.getForObject("/api/networth", Map.class);
        assertThat(new BigDecimal(after.get("total").toString())).isEqualByComparingTo(total.subtract(new BigDecimal("14200.50")));
        assertThat(new BigDecimal(after.get("total").toString())).isEqualByComparingTo(sumOfLatestSnapshotsInDb());
    }

    @Test
    void aManualAccountShowsItsUpdatedDate() {
        Map<String, Object> fid = manual("Fidelity 401(k) · former employer", "investment");
        assertThat(fid).containsEntry("isManual", true).containsEntry("role", "balance_only").containsEntry("balanceAsOf", null);

        balance(fid.get("id"), "2026-10-01", "22940");
        Map<String, Object> row = findIn(http.getForObject("/api/networth", Map.class), "Fidelity 401(k) · former employer");
        assertThat(row).containsEntry("isManual", true).containsEntry("asOf", "2026-10-01").containsEntry("source", "manual");
        assertThat(new BigDecimal(row.get("current").toString())).isEqualByComparingTo("22940");
        assertThat(row.get("kind")).isEqualTo("Retirement");

        // A newer entry moves the date; an entry for the same day replaces it.
        balance(fid.get("id"), "2026-10-20", "23100");
        balance(fid.get("id"), "2026-10-20", "23150.25");
        row = findIn(http.getForObject("/api/networth", Map.class), "Fidelity 401(k) · former employer");
        assertThat(row).containsEntry("asOf", "2026-10-20");
        assertThat(new BigDecimal(row.get("current").toString())).isEqualByComparingTo("23150.25");
        assertThat(jdbc.queryForObject("select count(*) from balance_snapshot", Integer.class)).isEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> findIn(Map<String, Object> nw, String name) {
        return ((List<Map<String, Object>>) nw.get("groups")).stream()
                .flatMap(g -> ((List<Map<String, Object>>) g.get("accounts")).stream())
                .filter(a -> name.equals(a.get("name"))).findFirst().orElseThrow();
    }

    @Test
    void manualBalanceRules() throws Exception {
        sample.load(Path.of("../reference/sample-data"));
        Map<String, Object> m = manual("Old 401(k)", "investment");
        assertThat(balance(m.get("id"), "2026-10-22", "1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // tomorrow in New York
        assertThat(balance(m.get("id"), "1980-01-01", "1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(balance(m.get("id"), "2026-10-21", "1.234").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(balance(m.get("id"), "not-a-date", "1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(balance(m.get("id"), "2026-10-21", "1").getStatusCode()).isEqualTo(HttpStatus.OK); // today is fine
        String linkedLike = jdbc.queryForObject("select id::text from account where name = 'Capital One'", String.class);
        assertThat(balance(linkedLike, "2026-10-21", "1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(balance(java.util.UUID.randomUUID(), "2026-10-21", "1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void creatingAndEditingAccounts() throws Exception {
        sample.load(Path.of("../reference/sample-data")); // first: sample data never loads next to a real account
        assertThat(http.postForEntity("/api/accounts/manual", Map.of("name", "X", "type", "crypto"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/api/accounts/manual", Map.of("name", " ", "type", "other"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> m = manual("  HSA  ", "depository");
        assertThat(m).containsEntry("name", "HSA");
        assertThat((String) m.get("displayColor")).matches("#[0-9a-f]{6}");

        assertThat(patch(m.get("id"), Map.of("name", "Health savings", "displayColor", "#A0522D")).getBody())
                .containsEntry("name", "Health savings").containsEntry("displayColor", "#a0522d");
        assertThat(patch(m.get("id"), Map.of("displayColor", "red")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(patch(m.get("id"), Map.of("name", "")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(patch(java.util.UUID.randomUUID(), Map.of("hidden", true)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Renaming and hiding also work on linked / sample accounts, and the ledger respects hidden.
        int before = (int) http.getForObject("/api/transactions?from=2026-10&to=2026-10", Map.class).get("total");
        String amex = jdbc.queryForObject("select id::text from account where name = 'American Express'", String.class);
        assertThat(patch(amex, Map.of("name", "Amex Gold", "hidden", true)).getBody()).containsEntry("name", "Amex Gold").containsEntry("hidden", true);
        assertThat((int) http.getForObject("/api/transactions?from=2026-10&to=2026-10", Map.class).get("total")).isLessThan(before);
    }
}
