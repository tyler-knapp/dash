package com.tylerknapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaMigrationTest extends PostgresTestBase {
    @Autowired
    JdbcTemplate jdbc;

    /** Same order and names as CATS in reference/prototype.html. */
    static final Map<String, List<String>> PROTOTYPE_TAXONOMY = new LinkedHashMap<>();
    static {
        PROTOTYPE_TAXONOMY.put("Housing", List.of("Rent", "Electric", "Internet", "Renters Insurance"));
        PROTOTYPE_TAXONOMY.put("Food", List.of("Groceries", "Restaurants", "Coffee"));
        PROTOTYPE_TAXONOMY.put("Transportation", List.of("Gas", "Car Insurance", "Maintenance", "Parking"));
        PROTOTYPE_TAXONOMY.put("Health & Fitness", List.of("Gym", "Race Fees", "Running Gear", "Supplements", "Medical"));
        PROTOTYPE_TAXONOMY.put("Subscriptions", List.of("Software & AI", "Streaming", "Research Tools"));
        PROTOTYPE_TAXONOMY.put("Lifestyle", List.of("Clothing", "Haircuts", "Events", "Travel", "Gifts"));
        PROTOTYPE_TAXONOMY.put("Side Hustles", List.of("Reselling Inventory", "YouTube Gear"));
        PROTOTYPE_TAXONOMY.put("Income", List.of("Salary", "Interest", "Reselling Sales"));
        PROTOTYPE_TAXONOMY.put("Transfer", List.of("Card Payment", "Account Transfer", "Retirement Contribution"));
    }

    @Test
    void allTablesExist() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public' order by table_name",
                String.class);
        assertThat(tables).contains("item", "account", "transaction", "category", "subcategory",
                "budget_version", "balance_snapshot", "category_rule");
    }

    @Test
    void taxonomyMatchesPrototypeInOrder() {
        List<String> cats = jdbc.queryForList("select name from category order by sort_order", String.class);
        assertThat(cats).containsExactlyElementsOf(PROTOTYPE_TAXONOMY.keySet());

        PROTOTYPE_TAXONOMY.forEach((cat, subs) -> assertThat(jdbc.queryForList(
                "select name from subcategory where category_name = ? order by sort_order", String.class, cat))
                .as(cat).containsExactlyElementsOf(subs));
    }

    @Test
    void onlyIncomeAndTransferAreNotSpend() {
        assertThat(jdbc.queryForList("select name from category where kind = 'income'", String.class))
                .containsExactly("Income");
        assertThat(jdbc.queryForList("select name from category where kind = 'transfer'", String.class))
                .containsExactly("Transfer");
        assertThat(jdbc.queryForObject("select count(*) from category where kind = 'spend'", Integer.class))
                .isEqualTo(7);
    }

    @Test
    void transactionRejectsSubcategoryOutsideTaxonomy() {
        var acct = jdbc.queryForObject(
                "insert into account (name, role, is_manual) values ('Test', 'ledger', true) returning id",
                java.util.UUID.class);
        // Valid pair is accepted.
        jdbc.update("insert into transaction (account_id, date, amount, category, subcategory) "
                + "values (?, '2026-10-01', -12.50, 'Food', 'Coffee')", acct);
        // Subcategory from another category is rejected.
        assertThatThrownBy(() -> jdbc.update("insert into transaction (account_id, date, amount, category, subcategory) "
                + "values (?, '2026-10-01', -12.50, 'Food', 'Gas')", acct))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Category without subcategory is rejected.
        assertThatThrownBy(() -> jdbc.update("insert into transaction (account_id, date, amount, category) "
                + "values (?, '2026-10-01', -12.50, 'Food')", acct))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("delete from account where id = ?", acct);
    }

    @Test
    void manualAccountCannotHavePlaidIds() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into account (name, role, is_manual, plaid_account_id) values ('X', 'balance_only', true, 'abc')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void budgetMonthFormatIsEnforced() {
        assertThatThrownBy(() -> jdbc.update("insert into budget_version (effective_from) values ('2026-13')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
