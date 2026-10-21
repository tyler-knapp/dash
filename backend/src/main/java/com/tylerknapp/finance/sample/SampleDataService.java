package com.tylerknapp.finance.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the kit's fake data (reference/sample-data) for development, tagged {@code is_sample} so
 * it never mixes with real data. Refuses to load once any real account exists, and is removed
 * automatically when the first real institution is linked.
 */
@Service
public class SampleDataService {
    private static final Logger log = LoggerFactory.getLogger(SampleDataService.class);

    /** The prototype's accounts (ACCTS in reference/prototype.html): name, Plaid-style type/subtype, role, color. */
    record SampleAccount(String key, String name, String type, String subtype, String role, String color) {}

    static final List<SampleAccount> ACCOUNTS = List.of(
            new SampleAccount("hv", "Huntington Voice Rewards", "credit", "credit card", "ledger", "#2f7d4f"),
            new SampleAccount("amex", "American Express", "credit", "credit card", "ledger", "#2b6cb0"),
            new SampleAccount("c1", "Capital One", "credit", "credit card", "ledger", "#b4413a"),
            new SampleAccount("hchk", "Huntington Checking", "depository", "checking", "ledger", "#3d8b5f"),
            new SampleAccount("53chk", "Fifth Third Checking", "depository", "checking", "ledger", "#1f6b5c"),
            new SampleAccount("53sav", "Fifth Third Savings", "depository", "savings", "ledger", "#4f9a86"),
            new SampleAccount("sofichk", "SoFi Checking", "depository", "checking", "ledger", "#6b5bb4"),
            new SampleAccount("sofisav", "SoFi Savings", "depository", "savings", "ledger", "#8a7dd0"),
            new SampleAccount("vgmm", "Vanguard Money Market", "depository", "money market", "ledger", "#a0522d"),
            new SampleAccount("vgroth", "Vanguard Roth IRA", "investment", "roth", "balance_only", null),
            new SampleAccount("empower", "Empower 401(k)", "investment", "401k", "balance_only", null),
            new SampleAccount("fid401k", "Fidelity 401(k) · former employer", "investment", "401k", "balance_only", null));

    public record Result(String outcome, int accounts, int transactions, int budgetVersions) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public SampleDataService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public boolean present() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from account where is_sample)", Boolean.class));
    }

    private boolean realAccountsExist() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from account where not is_sample)", Boolean.class));
    }

    @Transactional
    public Result load(Path dir) throws IOException {
        if (realAccountsExist()) {
            log.info("Sample data not loaded: real accounts exist");
            return new Result("skipped_real_data", 0, 0, 0);
        }
        if (present()) {
            return new Result("already_loaded", 0, 0, 0);
        }
        JsonNode balances = json.readTree(dir.resolve("balances.json").toFile());
        Map<String, JsonNode> balanceByKey = new HashMap<>();
        balances.path("accounts").forEach(a -> balanceByKey.put(a.path("id").asText(), a));
        LocalDate asOf = LocalDate.parse(balances.path("asOf").asText());

        Map<String, UUID> ids = new HashMap<>();
        for (SampleAccount a : ACCOUNTS) {
            JsonNode bal = balanceByKey.get(a.key());
            boolean manual = bal != null && "manual".equals(bal.path("source").asText());
            UUID id = jdbc.queryForObject("""
                    insert into account (name, type, subtype, role, display_color, is_manual, is_sample)
                    values (?, ?, ?, ?, ?, ?, true) returning id""", UUID.class,
                    a.name(), a.type(), a.subtype(), a.role(), a.color(), manual);
            ids.put(a.key(), id);
            if (bal != null) {
                LocalDate day = manual && bal.hasNonNull("updated") ? LocalDate.parse(bal.path("updated").asText()) : asOf;
                jdbc.update("insert into balance_snapshot (account_id, as_of, current, source) values (?, ?, ?, ?)",
                        id, day, bal.path("balance").decimalValue(), manual ? "manual" : "plaid");
            }
        }

        int txns = 0;
        List<Path> months = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "[0-9][0-9][0-9][0-9]-[0-9][0-9].json")) {
            files.forEach(months::add);
        }
        months.sort(null);
        List<Object[]> rows = new ArrayList<>();
        for (Path f : months) {
            for (JsonNode t : json.readTree(f.toFile()).path("transactions")) {
                UUID account = ids.get(t.path("account").asText());
                if (account == null) {
                    throw new IllegalStateException("Sample transaction for unknown account " + t.path("account").asText());
                }
                boolean edited = t.path("edited").asBoolean();
                rows.add(new Object[] {account, LocalDate.parse(t.path("date").asText()), t.path("merchant").asText(),
                        t.path("merchant").asText(), new BigDecimal(t.path("amount").asText()), t.path("category").asText(),
                        t.path("sub").asText(), edited ? "user" : "ai", new BigDecimal(t.path("confidence").asText()), edited});
            }
        }
        jdbc.batchUpdate("""
                insert into transaction (account_id, date, merchant_name, raw_name, amount, category, subcategory,
                                         category_source, confidence, reviewed)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);
        txns = rows.size();

        int versions = 0;
        for (JsonNode v : json.readTree(dir.resolve("budgets.json").toFile()).path("history")) {
            versions += jdbc.update("""
                    insert into budget_version (effective_from, amounts, is_sample) values (?, ?::jsonb, true)
                    on conflict (effective_from) do nothing""", v.path("from").asText(), v.path("amounts").toString());
        }
        log.info("Loaded sample data: {} accounts, {} transactions, {} budget versions", ids.size(), txns, versions);
        return new Result("loaded", ids.size(), txns, versions);
    }

    /** Delete every sample row (transactions and balances go with their accounts). */
    @Transactional
    public int clear() {
        int n = jdbc.update("delete from account where is_sample");
        jdbc.update("delete from budget_version where is_sample");
        if (n > 0) {
            log.info("Removed sample data ({} accounts)", n);
        }
        return n;
    }
}
