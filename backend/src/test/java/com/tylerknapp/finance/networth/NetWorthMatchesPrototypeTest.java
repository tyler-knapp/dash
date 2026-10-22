package com.tylerknapp.finance.networth;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** /api/networth against what the prototype's Net worth tab shows for the sample data (scripts/prototype-oracle). */
@Import(FixedClockConfig.class)
class NetWorthMatchesPrototypeTest extends PostgresTestBase {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SampleDataService sample;
    @Autowired ObjectMapper json;

    static String fmt(BigDecimal n, boolean cents) {
        DecimalFormat f = new DecimalFormat(cents ? "#,##0.00" : "#,##0", DecimalFormatSymbols.getInstance(Locale.US));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return (n.signum() < 0 ? "−" : "") + "$" + f.format(n.abs());
    }

    /** The prototype's meta line: "Manual · updated Oct 1", "Retirement · linked", or the kind. */
    static String meta(Map<String, Object> a) {
        if (Boolean.TRUE.equals(a.get("isManual"))) {
            return "Manual · updated " + LocalDate.parse((String) a.get("asOf")).format(DateTimeFormatter.ofPattern("MMM d", Locale.US));
        }
        String kind = (String) a.get("kind");
        return "Retirement".equals(kind) ? kind + " · linked" : kind;
    }

    @Test
    @SuppressWarnings("unchecked")
    void matchesThePrototypesNetWorthTab() throws Exception {
        jdbc.execute("truncate item, account, transaction, balance_snapshot, budget_version cascade");
        sample.load(Path.of("../reference/sample-data"));
        JsonNode exp = json.readTree(getClass().getResourceAsStream("/prototype-oracle/networth-2026-10-21.json"));

        Map<String, Object> nw = http.getForObject("/api/networth", Map.class);

        assertThat(fmt(new BigDecimal(nw.get("total").toString()), false)).isEqualTo(exp.at("/summary/0/1").asText());
        assertThat(fmt(new BigDecimal(nw.get("cashMinusCards").toString()), false)).isEqualTo(exp.at("/summary/1/1").asText());
        assertThat(LocalDate.parse((String) nw.get("asOf")).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)))
                .isEqualTo(exp.path("note").asText().replaceAll("^Balances as of (.*?)\\. .*$", "$1"));

        List<Map<String, Object>> groups = (List<Map<String, Object>>) nw.get("groups");
        JsonNode expGroups = exp.path("groups");
        assertThat(groups).hasSize(expGroups.size());
        for (int g = 0; g < groups.size(); g++) {
            Map<String, Object> grp = groups.get(g);
            JsonNode eg = expGroups.get(g);
            assertThat(grp.get("name")).isEqualTo(eg.path("title").asText());
            assertThat(fmt(new BigDecimal(grp.get("total").toString()), true)).isEqualTo(eg.path("total").asText());
            List<Map<String, Object>> accts = (List<Map<String, Object>>) grp.get("accounts");
            assertThat(accts).hasSize(eg.path("rows").size());
            for (int i = 0; i < accts.size(); i++) {
                Map<String, Object> a = accts.get(i);
                JsonNode er = eg.path("rows").get(i);
                assertThat(a.get("name")).isEqualTo(er.path("name").asText());
                assertThat(meta(a)).as((String) a.get("name")).isEqualTo(er.path("meta").asText());
                BigDecimal cur = new BigDecimal(a.get("current").toString());
                assertThat(fmt(cur, true)).isEqualTo(er.path("amount").asText());
                assertThat(cur.signum() < 0).isEqualTo(er.path("negative").asBoolean());
            }
        }
    }
}
