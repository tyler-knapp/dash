package com.tylerknapp.finance.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tylerknapp.finance.FixedClockConfig;
import com.tylerknapp.finance.PostgresTestBase;
import com.tylerknapp.finance.sample.SampleDataService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * /api/summary against what the approved prototype itself shows for the same sample data and the
 * same day. The expected values were recorded by running reference/prototype.html in Chromium
 * (scripts/prototype-oracle) with the clock frozen at 2026-10-21, New York time.
 */
@Import(FixedClockConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BudgetMatchesPrototypeTest extends PostgresTestBase {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SampleDataService sample;
    @Autowired ObjectMapper json;

    JsonNode oracle;

    @BeforeAll
    void load() throws Exception {
        jdbc.execute("truncate item, account, transaction, balance_snapshot, budget_version cascade");
        sample.load(Path.of("../reference/sample-data"));
        oracle = json.readTree(getClass().getResourceAsStream("/prototype-oracle/budget-2026-10-21.json"));
        assertThat(oracle.path("today").asText()).isEqualTo("2026-10-21");
    }

    /** The prototype's fmt(): "$1,234.56" / "$1,235", real minus sign. */
    static String fmt(BigDecimal n, boolean cents) {
        DecimalFormat f = new DecimalFormat(cents ? "#,##0.00" : "#,##0", DecimalFormatSymbols.getInstance(Locale.US));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return (n.signum() < 0 ? "−" : "") + "$" + f.format(n.abs());
    }

    static BigDecimal dec(Object o) {
        return new BigDecimal(o.toString());
    }

    static double pct(String css) { // "61.4747%" or "calc(67.7419% - 1px)"
        return Double.parseDouble(css.replaceAll("[^0-9.]+", " ").trim().split(" ")[0]);
    }

    @TestFactory
    Stream<DynamicTest> everyRecordedPeriodMatches() {
        List<DynamicTest> tests = new ArrayList<>();
        oracle.path("scenarios").fields().forEachRemaining(e -> tests.add(DynamicTest.dynamicTest(e.getKey(), () -> check(e.getValue()))));
        assertThat(tests).hasSize(11);
        return tests.stream();
    }

    @SuppressWarnings("unchecked")
    void check(JsonNode exp) {
        JsonNode months = exp.path("months");
        String from = months.get(0).asText();
        String to = months.get(months.size() - 1).asText();
        Map<String, Object> s = http.getForObject("/api/summary?from=" + from + "&to=" + to, Map.class);

        // Summary strip and header count.
        assertThat(fmt(dec(s.get("income")), true)).isEqualTo(exp.at("/summary/income").asText());
        assertThat(fmt(dec(s.get("spent")), true)).isEqualTo(exp.at("/summary/spent").asText());
        assertThat(fmt(dec(s.get("budgeted")), false)).isEqualTo(exp.at("/summary/budgeted").asText());
        BigDecimal net = dec(s.get("net"));
        assertThat((net.signum() >= 0 ? "+" : "") + fmt(net, true)).isEqualTo(exp.at("/summary/net").asText());
        int count = (int) s.get("transactionCount");
        assertThat(exp.path("sub").asText()).endsWith("· " + count + " transaction" + (count == 1 ? "" : "s"));

        List<Map<String, Object>> cats = (List<Map<String, Object>>) s.get("categories");
        JsonNode expCats = exp.path("categories");
        assertThat(cats).hasSize(expCats.size());
        boolean showPace = "current".equals(s.get("periodState"));
        for (int i = 0; i < cats.size(); i++) {
            Map<String, Object> c = cats.get(i);
            JsonNode x = expCats.get(i);
            String name = x.path("name").asText();
            assertThat(c.get("name")).isEqualTo(name);
            assertThat(c.get("statusLabel")).as(name + " label").isEqualTo(x.path("statusLabel").asText());
            assertThat(c.get("status")).as(name + " status").isEqualTo(x.path("status").asText());
            BigDecimal spent = dec(c.get("spent"));
            BigDecimal budget = dec(c.get("budget"));
            assertThat(fmt(spent, true)).as(name + " spent").isEqualTo(x.path("spent").asText());
            // Month view shows the raw number in the input; other views show fmt(b, false).
            String shownBudget = exp.path("editable").asBoolean() ? budget.stripTrailingZeros().toPlainString() : fmt(budget, false);
            assertThat(shownBudget).as(name + " budget").isEqualTo(x.path("budget").asText());
            double barWidth = budget.signum() == 0 ? 0 : Math.min(spent.doubleValue() / budget.doubleValue(), 1) * 100;
            assertThat(barWidth).as(name + " bar").isCloseTo(pct(x.path("barWidth").asText()), within(1e-3));
            if (showPace && budget.signum() > 0) {
                double pace = Math.min(dec(c.get("expectedToDate")).doubleValue() / budget.doubleValue(), 1) * 100;
                assertThat(pace).as(name + " pace marker").isCloseTo(pct(x.path("paceLeft").asText()), within(1e-3));
            } else {
                assertThat(x.path("paceLeft").isNull()).as(name + " no pace marker").isTrue();
            }
            List<Map<String, Object>> subs = (List<Map<String, Object>>) c.get("subs");
            JsonNode expSubs = x.path("subs");
            assertThat(subs).hasSize(expSubs.size());
            for (int j = 0; j < subs.size(); j++) {
                assertThat(subs.get(j).get("name")).isEqualTo(expSubs.get(j).get(0).asText());
                assertThat(fmt(dec(subs.get(j).get("spent")), true)).as(name + " sub").isEqualTo(expSubs.get(j).get(1).asText());
            }
        }
    }
}
