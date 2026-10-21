package com.tylerknapp.finance.store;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {
    private final JdbcTemplate jdbc;

    public TransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Plaid-owned fields of a transaction, already in app sign. */
    public record PlaidFields(UUID accountId, String plaidTransactionId, String pendingPlaidTransactionId,
                              LocalDate date, LocalDate authorizedDate, String merchantName, String rawName,
                              BigDecimal amount, boolean pending, String pfcPrimary, String pfcDetailed,
                              String pfcConfidence) {}

    /** Category fields. All null = uncategorized. */
    public record CategoryFields(String category, String subcategory, String source, BigDecimal confidence,
                                 boolean reviewed) {
        public static final CategoryFields NONE = new CategoryFields(null, null, null, null, false);
    }

    /**
     * Insert a newly added transaction. If Plaid sends one we already have (a replayed page),
     * refresh the Plaid fields only, so an existing category is never overwritten.
     */
    public void insertAdded(PlaidFields f, CategoryFields c) {
        jdbc.update("""
                insert into transaction (account_id, plaid_transaction_id, pending_plaid_transaction_id, date,
                    authorized_date, merchant_name, raw_name, amount, pending, category, subcategory,
                    category_source, confidence, reviewed, plaid_pfc_primary, plaid_pfc_detailed, plaid_pfc_confidence)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (plaid_transaction_id) do update set
                """ + PLAID_FIELDS_FROM_EXCLUDED,
                f.accountId(), f.plaidTransactionId(), f.pendingPlaidTransactionId(), f.date(), f.authorizedDate(),
                f.merchantName(), f.rawName(), f.amount(), f.pending(), c.category(), c.subcategory(), c.source(),
                c.confidence(), c.reviewed(), f.pfcPrimary(), f.pfcDetailed(), f.pfcConfidence());
    }

    /** Update Plaid fields of a modified transaction; category fields are untouched. @return rows updated */
    public int updateModified(PlaidFields f) {
        return jdbc.update("""
                update transaction set account_id = ?, pending_plaid_transaction_id = ?, date = ?, authorized_date = ?,
                    merchant_name = ?, raw_name = ?, amount = ?, pending = ?, plaid_pfc_primary = ?,
                    plaid_pfc_detailed = ?, plaid_pfc_confidence = ?, updated_at = now()
                where plaid_transaction_id = ?""",
                f.accountId(), f.pendingPlaidTransactionId(), f.date(), f.authorizedDate(), f.merchantName(),
                f.rawName(), f.amount(), f.pending(), f.pfcPrimary(), f.pfcDetailed(), f.pfcConfidence(),
                f.plaidTransactionId());
    }

    public int deleteByPlaidIds(Collection<String> plaidTransactionIds) {
        int n = 0;
        for (String id : plaidTransactionIds) {
            n += jdbc.update("delete from transaction where plaid_transaction_id = ?", id);
        }
        return n;
    }

    public Optional<CategoryFields> findCategory(String plaidTransactionId) {
        return jdbc.query("""
                select category, subcategory, category_source, confidence, reviewed
                from transaction where plaid_transaction_id = ?""",
                (rs, i) -> new CategoryFields(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBoolean(5)), plaidTransactionId).stream().findFirst();
    }

    private static final String PLAID_FIELDS_FROM_EXCLUDED = """
            account_id = excluded.account_id, pending_plaid_transaction_id = excluded.pending_plaid_transaction_id,
            date = excluded.date, authorized_date = excluded.authorized_date, merchant_name = excluded.merchant_name,
            raw_name = excluded.raw_name, amount = excluded.amount, pending = excluded.pending,
            plaid_pfc_primary = excluded.plaid_pfc_primary, plaid_pfc_detailed = excluded.plaid_pfc_detailed,
            plaid_pfc_confidence = excluded.plaid_pfc_confidence, updated_at = now()""";
}
