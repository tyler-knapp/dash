package com.tylerknapp.finance.store;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ItemRepository {
    private final JdbcTemplate jdbc;

    public ItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Item row. {@code accessTokenEnc} is ciphertext; decrypt only right before a Plaid call. */
    public record ItemRow(UUID id, String plaidItemId, String institutionId, String institutionName,
                          byte[] accessTokenEnc, String syncCursor, String status, OffsetDateTime lastSyncedAt,
                          List<String> products, String transactionsUpdateStatus, String lastErrorCode) {
        public boolean hasProduct(String p) {
            return products.contains(p);
        }

        @Override
        public String toString() {
            return "ItemRow[id=" + id + ", institution=" + institutionName + ", status=" + status + "]";
        }
    }

    public UUID upsert(String plaidItemId, String institutionId, String institutionName, byte[] tokenEnc,
                       List<String> products) {
        return jdbc.queryForObject("""
                insert into item (plaid_item_id, institution_id, institution_name, access_token_enc, products)
                values (?, ?, ?, ?, ?::text[])
                on conflict (plaid_item_id) do update set
                    institution_id = excluded.institution_id, institution_name = excluded.institution_name,
                    access_token_enc = excluded.access_token_enc, products = excluded.products,
                    status = 'ok', last_error_code = null
                returning id""", UUID.class,
                plaidItemId, institutionId, institutionName, tokenEnc, pgArray(products));
    }

    public Optional<ItemRow> find(UUID id) {
        return jdbc.query("select * from item where id = ?", ItemRepository::map, id).stream().findFirst();
    }

    public List<ItemRow> findAll() {
        return jdbc.query("select * from item order by created_at", ItemRepository::map);
    }

    public void saveCursor(UUID id, String cursor, String updateStatus) {
        jdbc.update("update item set sync_cursor = ?, transactions_update_status = coalesce(?, transactions_update_status) where id = ?",
                cursor, updateStatus, id);
    }

    public void markOk(UUID id) {
        jdbc.update("update item set status = 'ok', last_error_code = null, last_synced_at = now() where id = ?", id);
    }

    public void markError(UUID id, String status, String errorCode) {
        jdbc.update("update item set status = ?, last_error_code = ? where id = ?", status, errorCode, id);
    }

    private static ItemRow map(ResultSet rs, int i) throws SQLException {
        Array products = rs.getArray("products");
        return new ItemRow(rs.getObject("id", UUID.class), rs.getString("plaid_item_id"),
                rs.getString("institution_id"), rs.getString("institution_name"), rs.getBytes("access_token_enc"),
                rs.getString("sync_cursor"), rs.getString("status"),
                rs.getObject("last_synced_at", OffsetDateTime.class),
                products == null ? List.of() : Arrays.asList((String[]) products.getArray()),
                rs.getString("transactions_update_status"), rs.getString("last_error_code"));
    }

    /** Postgres array literal for a small list of simple identifiers (Plaid product names). */
    static String pgArray(List<String> values) {
        for (String v : values) {
            if (!v.matches("[a-z_]+")) {
                throw new IllegalArgumentException("Unexpected product name: " + v);
            }
        }
        return "{" + String.join(",", values) + "}";
    }
}
