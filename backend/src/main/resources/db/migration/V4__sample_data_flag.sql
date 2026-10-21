-- Dev sample data (reference/sample-data) is tagged so it never mixes with real data:
-- it can be found, shown with a "Sample data" badge, and deleted in one go.
ALTER TABLE account ADD COLUMN is_sample BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE budget_version ADD COLUMN is_sample BOOLEAN NOT NULL DEFAULT false;

-- Sample accounts aren't linked to Plaid, but some of them stand in for linked accounts.
ALTER TABLE account DROP CONSTRAINT account_manual_vs_linked;
ALTER TABLE account ADD CONSTRAINT account_manual_vs_linked CHECK (
    (is_sample AND item_id IS NULL AND plaid_account_id IS NULL)
    OR (NOT is_sample AND is_manual AND item_id IS NULL AND plaid_account_id IS NULL)
    OR (NOT is_sample AND NOT is_manual AND item_id IS NOT NULL AND plaid_account_id IS NOT NULL)
);
